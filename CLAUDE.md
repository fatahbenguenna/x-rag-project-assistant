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
2. **LLM** : Ollama + `qwen2.5:7b-instruct` (Q4) par défaut ; fallback `qwen2.5:3b` pour questions descriptives. Architecture **commutable** via `ChatClient` Spring AI : profil `ollama` (local, confidentiel) ou `gemini`/endpoint OpenAI-compatible (si politique de confidentialité OK). Routage possible par type de question.
3. **Embeddings** : `bge-m3` via Ollama (multilingue FR + code), toujours en local.
4. **Vector store + graphe** : PostgreSQL + pgvector. Le graphe vit dans Postgres (tables `graph_nodes` / `graph_edges`), pas de Neo4j. Requêtes de voisinage via `WITH RECURSIVE` (profondeur 2).
5. **GraphRAG hybride** : retrieval = (1) détection d'entités via table d'alias, (2) expansion graphe profondeur 2, (3) sous-graphe sérialisé en texte compact injecté au prompt, (4) recherche vectorielle boostée par les chunks rattachés aux nœuds du sous-graphe. Recherche hybride vecteur + full-text (`tsvector`), filtre métadonnées par projet.
6. **Tools (function calling)** pour les questions factuelles/structurées (MRs ouvertes, tris, comptages) : table SQL de métadonnées MR + tools Spring AI type `listOpenMRs(sortBy=createdAt)`. Le RAG seul répond mal à ces questions.
7. **Fiches projet pré-calculées** : job nocturne qui génère par projet une synthèse structurée (stack, architecture, modèle de données, endpoints, events publiés/consommés, dépendances issues du graphe). Indexées comme documents premium. Indispensables pour les réponses <20 s.
8. **Streaming obligatoire** sur l'endpoint chat (premier token ~2-5 s en CPU).
9. **Migrations : Liquibase** (choix explicite de Hassen, PAS Flyway).
10. **Extraction de relations : déterministe d'abord** (regex, JavaParser, parsing TS), LLM nocturne seulement si l'éval montre des trous.

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
  easyloc: ["Easy Loc", "easy-loc", "EASYLOC"]
schedule: { nightly: "0 0 2 * * *" }
extractors: { java: true, typescript: true, python: false }
```

- Secrets en variables d'environnement (`.env`), jamais dans le YAML.
- Interfaces plugin : `SourceConnector` (Confluence/GitLab/Jira) et `RelationExtractor` (Java/TS/...), activées par config. Architecture hexagonale : connecteurs = adapters, pipeline d'ingestion = domaine.
- Images Docker versionnées sur registry privé (registry self-hosted + Tailscale de Hassen). Les équipes font `docker compose pull && up -d` ; Liquibase migre au démarrage.
- Onboarding cible < 1 h : clone → `.env` → `team-config.yml` → `docker compose up` → `./bootstrap.sh` (indexation initiale, 3-6 h la première nuit) → smoke test.

## Stack technique

- Backend : Java 21, Spring Boot, Spring AI (starters ollama + vertex-ai-gemini + pgvector), Liquibase, architecture hexagonale/DDD (voir skill `ddd-clean-architecture` si disponible).
- Base : PostgreSQL 16 + pgvector (image `pgvector/pgvector:pg16`), index HNSW.
- UI : Open WebUI au départ (ou front Angular custom ensuite).
- Compose services : `ollama`, `postgres`, `rag-api`, `open-webui` (optionnel).
- Contexte matériel de référence : Ryzen 7, 32 Go RAM partagée iGPU → inférence CPU, ~10-20 tokens/s en génération 7B Q4.

## Dépôt GitHub et plan de PRs

- Créer un **dépôt privé** `x-rag-project-assistant` sous **Domwil-Sarl** (vérifier : organisation ou compte utilisateur ? point resté ouvert).
- Workflow : une PR par étape, mergée avant d'ouvrir la suivante :
  1. `chore: bootstrap` — structure du repo, docker-compose, README d'onboarding, `.env.example`
  2. `feat: config` — `team-config.example.yml` + `@ConfigurationProperties`
  3. `feat: schema` — changelogs **Liquibase** (pgvector, chunks, graph_nodes/edges, métadonnées MR)
  4. `feat: connectors` — ports/adapters Confluence, GitLab, Jira
  5. `feat: extractors` — plugins Java/TS + résolution d'alias
  6. `feat: retrieval` — recherche hybride vecteur + graphe + tools, endpoint chat streamé
  7. `feat: nightly` — jobs schedulés, fiches projet, smoke tests, bootstrap.sh
- Auth : fine-grained PAT, resource owner Domwil-Sarl, permissions Administration (write) + Contents (write) + Pull requests (write), expiration courte, révocation après usage.

## Attentes de performance (cadrage validé)

| Type de question | Latence CPU visée |
|---|---|
| Factuel via tools (MRs) | 10-15 s |
| Résumé projet (via fiche) | 15-25 s complet, 1er token ~5 s |
| Synthèse trans-projets A×B | 45-90 s streamé (incompressible sans GPU ou API distante) |

Prompt système : réponses concises, max ~200 mots pour le descriptif, toujours citer les sources (page/fichier/MR).

## Effort estimé

Socle RAG + GraphRAG : ~2 semaines. Exportabilité (config, plugins, packaging, doc) : +3-4 jours. Chaque phase utilisable seule, incrémental.
