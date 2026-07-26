# x-rag-project-assistant — Contexte projet (handoff depuis claude.ai)

## Objectif

Assistant RAG d'équipe permettant à tout membre d'un projet de poser des questions sur :
- la documentation Confluence,
- le code source des projets GitLab (Java/Spring Boot + TypeScript/Angular),
- l'historique des Merge Requests GitLab,
- (3e source) les issues Jira.

Exemples de questions cibles : "explique-moi le projet Elog en 5 principes", "comment faire communiquer Easy Loc et Epsilon", "avons-nous eu un bug de persistance sur alpha ?", "quelle MR ouverte est la plus vieille ?".

**Produit exportable** : toute équipe Confluence/Jira/GitLab doit pouvoir déployer sa propre instance sans toucher au code, uniquement via configuration.

## Décisions d'architecture (validées, ne pas rediscuter)

1. **Hébergement centralisé** : une instance par équipe (Docker Compose autonome), pas de multi-tenant, pas de distribution sur chaque poste.
2. **LLM** : Ollama + `qwen2.5:7b-instruct` (Q4) par défaut. Fallback `qwen2.5:3b` **optionnel** via `llm.fallback-model` (actif dans `team-config.example.yml`, routé par `ModelRouter` sur les questions descriptives, sans tools) — le retirer de la config = un seul modèle. Architecture **commutable** via `ChatClient` Spring AI : profil `ollama` (local, confidentiel) ou `gemini` (si politique de confidentialité OK) — pas d'endpoint OpenAI-compatible implémenté. Ollama lui-même est **commutable conteneur / natif hôte (GPU) / WSL** via `.env` (`OLLAMA_BASE_URL` + `COMPOSE_PROFILES`, profil `ollama-docker` pour le conteneur). Options Ollama : `num_ctx=8192` (la fenêtre 2048 par défaut tronquait le prompt RAG → hedging) et `temperature=0.1`.
3. **Embeddings** : `bge-m3` via Ollama (multilingue FR + code), toujours en local.
4. **Vector store + graphe** : PostgreSQL + pgvector. Le graphe vit dans Postgres (tables `graph_nodes` / `graph_edges`), pas de Neo4j. Requêtes de voisinage via `WITH RECURSIVE` (profondeur 2).
5. **GraphRAG hybride** : retrieval = (1) détection d'entités via table d'alias, (2) expansion graphe profondeur 2, (3) sous-graphe sérialisé en texte compact injecté au prompt, (4) recherche vectorielle boostée par les chunks rattachés aux nœuds du sous-graphe. Recherche hybride vecteur + full-text (`tsvector`), filtre métadonnées par projet.
6. **Tools (function calling)** pour les questions factuelles/structurées : `listMergeRequests`, `searchMergeRequests` (par sujet, avec synonymes métier↔code de `team-config.synonyms`), `countMergeRequests` (table SQL de métadonnées MR), et `searchKnowledgeBase` (recherche plein-texte déterministe `tsvector` sur tous les chunks). Le RAG seul répond mal aux questions structurées. **Nuance apprise** : le function-calling d'un 7B est peu fiable → la pré-injection hybride reste le **socle garanti**, les tools sont un **filet** (le prompt incite à appeler `searchKnowledgeBase` quand les extraits ne suffisent pas). **Références exactes** (clé Jira, numéro de MR cités dans la question) : détection regex insensible à la casse → chargement déterministe du document complet (`documentChunks`/`findByIid`) → injecté **en fin de sources** marqué « RÉFÉRENCE EXACTE », avec bruit contextuel plafonné à 4 chunks — en tête et sans marquage, le 7B ignorait la référence au profit du bruit de similarité (biais de récence mesuré sur « décris la MR !153 »). Tools associés : `getIssue(key)`, `getMergeRequest(iid)`.
7. **Fiches projet pré-calculées** : job nocturne qui génère par projet une synthèse structurée (stack, architecture, modèle de données, endpoints, events publiés/consommés, dépendances issues du graphe). Indexées comme documents premium. Indispensables pour les réponses <20 s.
8. **Streaming obligatoire** sur l'endpoint chat (premier token ~2-5 s en CPU).
9. **Migrations : Liquibase** (choix explicite de Hassen, PAS Flyway). Changelog maître en **XML** (`db.changelog-master.xml`), changesets DDL en **SQL** (`.sql`). Jamais de YAML/JSON.
10. **Extraction de relations : déterministe d'abord** (regex, JavaParser, parsing TS). **Extraction LLM nocturne IMPLÉMENTÉE** (`GraphEnrichmentService`) : pour les documents non rattachés ou sans nœud `TOPIC` (surtout hors Java/TS), le LLM extrait des sujets → nœuds `TOPIC` + alias (retrouvables) → rattachement des chunks. Activée par `extractors.llm`, déclenchée quand l'éval `/api/admin/graph-quality` montre < 50 % de chunks rattachés ; plafonnée par nuit. Déclenchement manuel : `POST /api/admin/enrich[?sources=confluence,jira]`.

## Modèle de graphe

```sql
CREATE TABLE graph_nodes (
  id TEXT PRIMARY KEY,        -- "project:easyloc", "page:12345", "topic:orders"
  type TEXT NOT NULL,         -- PROJECT, PAGE, MR, ISSUE, CLASS, TABLE, TOPIC, ENDPOINT
  name TEXT NOT NULL,
  props JSONB DEFAULT '{}'
);
CREATE TABLE graph_edges (
  src TEXT REFERENCES graph_nodes(id),
  dst TEXT REFERENCES graph_nodes(id),
  type TEXT NOT NULL,         -- DEPENDS_ON, CALLS_API, SHARES_TABLE, PUBLISHES, CONSUMES, DOCUMENTS, MODIFIES, REFERENCES, LINKS_TO
  props JSONB DEFAULT '{}',
  PRIMARY KEY (src, dst, type)
);
-- + colonne node_ids TEXT[] sur la table des chunks vectoriels (pont RAG <-> graphe)
```

Extraction déterministe :
- Confluence : liens entre pages, labels, mentions de projets, clés Jira regex `[A-Z][A-Z0-9]+-\d+`.
- Java : JavaParser → `@Table`/`@Entity` (nœuds TABLE), `@FeignClient`/`WebClient` (CALLS_API), `@KafkaListener`/`KafkaTemplate` (CONSUMES/PUBLISHES).
- TypeScript : appels HttpClient + URLs d'environnement, imports inter-libs.
- MRs : fichiers touchés (MODIFIES), clés Jira dans titre/description.
- Jira : issue → projet, liens entre issues.

**Résolution d'entités** : table d'alias configurable ("Easy Loc" / `easy-loc` / `EASYLOC` → id canonique). Critique, sinon graphe fragmenté.

## Batch nocturne (cible : terminé ~02:45, ~30-45 min)

1. 02:00 Health check (Ollama, Postgres, tokens API) — échec = alerte + abandon, l'index de la veille reste servi. Règle : jamais de destruction d'index, upsert only.
2. Sync Confluence incrémentale (comparaison `version.number`).
3. Sync code : git fetch + diff SHA indexé→HEAD, re-embedding des seuls fichiers modifiés. Clé de chunk stable `source:path:chunk_index` pour upsert.
4. Sync MRs (`updated_after`) + upsert table métadonnées.
5. Extraction relations graphe.
6. Réconciliation/purge des chunks et nœuds orphelins.
7. VACUUM ANALYZE (index HNSW, pas de rebuild).
8. Génération/refresh des fiches projet (avec voisinage graphe).
9. Smoke test automatique (questions canoniques instanciées depuis la config) + notification.
10. 07:30 warm-up modèle (`OLLAMA_KEEP_ALIVE=24h` recommandé).

> Étape ajoutée (décision 10) : après le VACUUM et avant les fiches projet, si `extractors.llm` est actif **et** que l'éval montre < 50 % de chunks rattachés, **enrichissement LLM du graphe** (nœuds `TOPIC`), plafonné à ~150 docs/nuit.

En journée : webhooks GitLab (push + merge_request events) → upsert temps réel. Confluence reste à J-1.

## Exportabilité

- Un seul fichier `team-config.yml` pilote tout (aucun nom de projet en dur dans le code) :

```yaml
team: equipe-passerelle
llm:
  provider: ollama          # ou gemini / openai-compatible
  model: qwen2.5:7b-instruct
sources:
  confluence: { base-url: ..., spaces: [PASS, ARCHI] }
  gitlab: { base-url: ..., group: passerelle, branches: [main, develop] }  # découverte auto des repos du groupe
  jira: { base-url: ..., projects: [PASS, INFRA] }
aliases:
  easyloc: ["Easy Loc", "easy-loc", "EASYLOC"]   # -> nœuds project: du graphe
synonyms:
  pos: ["caisse"]                                 # métier<->code pour searchMergeRequests (n'affecte pas le graphe)
schedule: { nightly: "0 0 2 * * *" }
extractors: { java: true, typescript: true, python: false, llm: false }  # llm = enrichissement TOPIC nocturne (décision 10)
```

- Secrets en variables d'environnement (`.env`), jamais dans le YAML. Voir `.env.example` pour le mode Ollama (`OLLAMA_BASE_URL`, `COMPOSE_PROFILES`) et les 5 modes d'auth Atlassian (cookie / basic / bearer / scoped / oauth, bi-plateforme Cloud + Data Center).
- Interfaces plugin : `SourceConnector` (Confluence/GitLab/Jira) et `RelationExtractor` (Java/TS/...), activées par config. Architecture hexagonale : connecteurs = adapters, pipeline d'ingestion = domaine.
- Images Docker versionnées sur registry privé (registry self-hosted + Tailscale de Hassen). Les équipes font `docker compose pull && up -d` ; Liquibase migre au démarrage.
- Onboarding cible < 1 h : clone → `.env` → `team-config.yml` → `docker compose up` → `./bootstrap.sh` (indexation initiale, 3-6 h la première nuit) → smoke test.

## Stack technique

- Backend : Java 21, Spring Boot, Spring AI (starters ollama + vertex-ai-gemini + pgvector), Liquibase, architecture hexagonale/DDD (voir skill `ddd-clean-architecture` si disponible).
- Base : PostgreSQL 16 + pgvector (image `pgvector/pgvector:pg16`), index HNSW.
- UI : Open WebUI au départ (ou front Angular custom ensuite).
- Compose services : `postgres` + `rag-api` (base) ; `ollama` (profil `ollama-docker`, optionnel — sinon Ollama natif/WSL) ; `open-webui` (profil `ui`, optionnel).
- Contexte matériel de référence : Ryzen 7, 32 Go RAM partagée iGPU → inférence CPU, ~10-20 tokens/s en génération 7B Q4.

## Dépôt GitHub et plan de PRs

- Dépôt : **`fatahbenguenna/x-rag-project-assistant`** (privé, compte utilisateur GitHub — l'option Domwil-Sarl initialement envisagée n'a pas été retenue).
- Workflow : une PR par étape, mergée avant d'ouvrir la suivante. **Socle #1-#7 livré**, puis #9 bump Spring Boot 3.5.16 et #10 migration Spring Boot 4.1 + Spring AI 2.0 :
  1. `chore: bootstrap` — structure du repo, docker-compose, README d'onboarding, `.env.example`
  2. `feat: config` — `team-config.example.yml` + `@ConfigurationProperties`
  3. `feat: schema` — changelogs **Liquibase** (pgvector, chunks, graph_nodes/edges, métadonnées MR)
  4. `feat: connectors` — ports/adapters Confluence, GitLab, Jira
  5. `feat: extractors` — plugins Java/TS + résolution d'alias
  6. `feat: retrieval` — recherche hybride vecteur + graphe + tools, endpoint chat streamé
  7. `feat: nightly` — jobs schedulés, fiches projet, smoke tests, bootstrap.sh

### Évolutions livrées (brownfield, post-socle)

- **Auth Atlassian multi-mode** (#22) : 5 modes (cookie / basic / bearer / scoped / oauth) résolus par `AtlassianConnectionFactory`, bi-plateforme **Cloud (API v2/v3) + Data Center (v1/v2)**. Migration des dépréciations Cloud (Jira `/2/search`→`/3/search/jql`, Confluence v1→v2).
- **Robustesse chat** : chat réactif WebFlux — retrieval bloquant isolé sur `boundedElastic` (#23) ; tokens d'usage OpenAI exposés + contexte réduit (#25) ; correctif NPE sur paramètre de tool primitif (#28).
- **Recherche MR par sujet** (#26) : tool `searchMergeRequests` (scoring titre×3/corps×1, frontière de mot) + `synonyms` métier↔code (`caisse`↔`pos`).
- **Dashboard de monitoring** de l'indexation (#24, `/dashboard.html`).
- **Ollama commutable** conteneur / natif hôte / WSL via `.env` (#27).
- **Liquibase** : master YAML → **XML**, changesets SQL (#29). `full=true` force la ré-indexation.
- **Enrichissement LLM du graphe** (#30, décision 10) : nœuds `TOPIC` + alias pour les docs non rattachés ; étendu aux docs Confluence/Jira déjà rattachés + **indexation des commentaires** Jira/Confluence (#31). Endpoints `/api/admin/enrich`, `/api/admin/sync?source=`.
- **Qualité des réponses** : correctif **hedging** (#32) — prompt « le contexte fait autorité » + `num_ctx=8192` (la fenêtre 2048 tronquait les sources) + température 0.1 ; **tool `searchKnowledgeBase`** de recherche plein-texte déterministe (#33) + incitation à l'appeler sur un miss (#34).
- **Revue d'architecture par panel d'experts** (#36, `docs/revue-architecture-rag-2026-07.md`) : 8 agents `rag-expert` (4 revues dimensionnelles + débat contradictoire LangChain vs Spring AI arbitré). Verdict : **rester sur Spring AI**, reprendre 3 techniques nativement. Quick wins livrés (#37-#39) : canal lexical **OR + ts_rank** (l'AND implicite tuait le rappel : 0 vs 1914 chunks), chunks montrés **en entier** (8×1800, section `retrieval` de team-config), **règle d'ancrage bidirectionnelle** (réfutation sourcée — corrige le « Oui » halluciné ET le hedging), `synthesisChatClient` dédié (fiches débridées ×2,7), **ADMIN_TOKEN** sur les POST admin (PathPattern décodé — bypass %-encodé fermé).
- **Actions M de la feuille de route** (#40-#42) : **topics durables** (fusion à l'upsert — `sync?full=true` ne détruit plus l'enrichissement ; nightly par document ; GC des topics sans référence) ; **harness d'éval recall@k** (`eval.cases` de team-config, `GET /api/admin/eval`, rapport nocturne — baseline 7/10, misses = rappel pas classement) ; **reranker cross-encoder ONNX in-process** (DJL 0.36 + bge-reranker-v2-m3 int8 en volume, désactivé par défaut : +1 recall@4 et repêchage vivier 14→rang 1 MAIS ~30 s/question sur la cible CPU — activable par config sur infra adaptée ; boost graphe 0,1 avec rerank / 0,3 sans, le 0,3 évinçait des pertinents, prouvé par l'éval).
- **Graphe sain** (#44, H2 + M2 de la revue) : **dé-hub** — les topics ne pointent plus le nœud PROJECT (degré 853→366) mais leurs documents (707 arêtes topic→page/issue/class, recâblage nocturne idempotent) ; voisinage `WITH RECURSIVE` **déterministe** (ORDER BY profondeur, id) + **barrière anti-hub** (degré > 50 non traversé sauf graine). **Hygiène des topics** — extracteur durci (non-latin rejeté, blocklist de génériques, alias ≥ 4 alphanum), purge nocturne des topics hors latin étendu (⚠️ accents FR admis — une purge ASCII détruirait `topic:sécurité` en boucle), **neutralisation** des génériques df ≥ 40 (nœud gardé en marqueur anti-re-création) + restauration auto-réparatrice des alias légitimes. Calibrage PAR L'ÉVAL : le seuil 10 initial cassait le cas multi-tenant (rang 1→5), détecté et recalibré à 40. Les singletons ne sont pas purgés (valeur de découvrabilité démontrée).
- **Diagnostic assisté d'un expert RAG** (sous-agent ponctuel) : a identifié la troncature `num_ctx` comme vraie cause du hedging, et écarté LangChain4j (aucun gain vs Spring AI 2.0). Un agent réutilisable `rag-expert` n'a pas été installé.

- Auth GitHub : fine-grained PAT, resource owner fatahbenguenna, permissions Administration (write) + Contents (write) + Pull requests (write), expiration courte, révocation après usage.

## Attentes de performance (cadrage validé)

| Type de question | Latence CPU visée |
|---|---|
| Factuel via tools (MRs) | 10-15 s |
| Résumé projet (via fiche) | 15-25 s complet, 1er token ~5 s |
| Synthèse trans-projets A×B | 45-90 s streamé (incompressible sans GPU ou API distante) |

Prompt système (`LlmConfiguration.SYSTEM_PROMPT`) : réponses concises (~200 mots pour le descriptif), toujours citer les sources (page/fichier/MR/issue). **Anti-hedging** : le contexte fourni fait autorité, l'assistant EST l'interface (ne jamais renvoyer vers l'outil externe), règle absolue « si une source correspond, commence par Oui » ; appeler `searchKnowledgeBase` si les extraits ne suffisent pas avant de conclure à une absence.

## Effort estimé

Socle RAG + GraphRAG : ~2 semaines. Exportabilité (config, plugins, packaging, doc) : +3-4 jours. Chaque phase utilisable seule, incrémental.

## Méthode BMAD (installée)

- BMAD Method v6 (module `bmm`) est installé : runtime dans `_bmad/`, skills Claude Code dans `.claude/skills/` (invoquer `bmad-help` pour démarrer). Mise à jour via `npx bmad-method install` (action update).
- Config : `_bmad/core/config.yaml` et `_bmad/bmm/config.yaml` — communication et documents en **français**.
- Usage **brownfield** uniquement : les artefacts BMAD (PRD, architecture, epics, stories) concernent les évolutions futures, pas la re-spécification des PRs déjà mergées.
- Les décisions de ce CLAUDE.md restent la référence : les agents BMAD (Analyst, PM, Architect, Dev...) ne doivent pas remettre en cause la section « Décisions d'architecture ».
- Emplacements : artefacts de planification dans `_bmad-output/planning-artifacts/`, artefacts d'implémentation dans `_bmad-output/implementation-artifacts/`, connaissance long-terme dans `docs/` (voir `docs/README.md`).
