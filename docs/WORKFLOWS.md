# Workflows — les flux du système en schémas

Vue visuelle des flux clés. Chaque schéma renvoie au document qui fait autorité
(README pour le produit, RUNBOOK pour l'exploitation, CLAUDE.md pour les décisions
d'architecture) — les schémas illustrent, ils ne remplacent pas.

## 1. Quel modèle LLM répond à ma question ?

Le routage vers un fallback exige **deux conditions** : `llm.provider: ollama` **et**
`llm.fallback-model` renseigné dans `team-config.yml`. Sans les deux (défaut recommandé :
pas de fallback), **toutes** les questions vont au modèle principal, avec les tools.

> Schéma pour `llm.provider: ollama` (défaut). En `provider: gemini`, le modèle principal
> est l'API Gemini, le fallback est désactivé même s'il est renseigné — seuls les
> embeddings `bge-m3` restent toujours sur Ollama.

```mermaid
flowchart TD
    U["👤 Utilisateur"] -->|question| UI["Open WebUI :3000<br/>modèle « xrag-‹team› »"]
    U -->|"curl -N /api/chat"| API
    UI -->|"POST /v1/chat/completions"| API["rag-api"]
    API --> RAG["Pipeline GraphRAG<br/>(schéma 2)"]
    RAG --> EMB["bge-m3 — embedding de la question<br/>(retrieval seulement, ne répond jamais)"]
    RAG --> ROUTER{"ModelRouter :<br/>provider ollama ET fallback-model<br/>configuré ET question descriptive<br/>(« explique », « résume »…)<br/>SANS mot-clé factuel<br/>(« MR », « combien », « liste »…) ?"}
    ROUTER -->|"non — cas général"| MAIN["qwen2.5:7b-instruct<br/>avec tools"]
    ROUTER -->|oui| FB["fallback (ex. qwen2.5:3b)<br/>sans tools"]
    MAIN --> OLLAMA["Ollama<br/>(OLLAMA_BASE_URL — schéma 5)"]
    FB --> OLLAMA
    OLLAMA -->|"réponse streamée, sources citées"| U
```

## 2. Pipeline GraphRAG hybride — le chemin d'une question

Le cœur du système (décisions d'architecture n° 5 et 6 de CLAUDE.md) : le graphe
guide la recherche vectorielle, les références exactes court-circuitent la similarité.

```mermaid
flowchart TD
    Q["Question"] --> ED["① Détection d'entités<br/>(table d'alias configurable)"]
    ED --> GE["② Expansion graphe profondeur 2<br/>WITH RECURSIVE — déterministe,<br/>barrière anti-hub (degré > 50)"]
    Q --> EMB["③ Embedding bge-m3"]
    GE --> HS["④ Recherche hybride SQL :<br/>0,6 × similarité vectorielle + 0,25 × full-text OR (ts_rank)<br/>+ boost des chunks rattachés au sous-graphe<br/>(0,3 — ou 0,1 avec vivier élargi ~40 si reranker actif)"]
    EMB --> HS
    HS --> RR{"Reranker activé ?<br/>(retrieval.reranker — défaut : non)"}
    RR -->|"oui : reclasse le vivier"| CE["Cross-encoder ONNX<br/>bge-reranker-v2-m3 (in-process)"]
    RR -->|non| EX
    CE --> EX["⑤ Références exactes :<br/>clé Jira / n° de MR cités dans la question<br/>→ document complet chargé en SQL,<br/>injecté EN FIN de sources, marqué « RÉFÉRENCE EXACTE » ;<br/>contexte retrieval plafonné à 4 chunks<br/>+ consigne d'ancrage ajoutée au prompt"]
    EX --> PROMPT["⑥ Prompt : sous-graphe sérialisé<br/>+ sources numérotées + question"]
    PROMPT --> LLM["LLM (schéma 1)"]
    TOOLS["Tools (base locale uniquement) :<br/>searchKnowledgeBase · getIssue ·<br/>list/search/count/getMergeRequest"] <-->|"si les extraits ne suffisent pas —<br/>modèle principal uniquement,<br/>route fallback sans tools (schéma 1)"| LLM
    LLM -->|streaming| R["Réponse avec sources<br/>(page / fichier / MR / issue)"]
```

## 3. Batch nocturne

Règle d'or : **jamais de destruction d'index** — tout est upsert à clés stables.
Toute exception après le health check déclenche une alerte et laisse l'index déjà
servi intact ; l'échec de sync d'une seule source n'interrompt pas les autres
(statut en erreur dans `sync_state`, le batch continue).

```mermaid
flowchart TD
    START(["02:00 — planifié<br/>(rattrapage : POST /api/admin/nightly, RUNBOOK §7)"]) --> HC{"Health check<br/>Postgres (SELECT 1)<br/>· Ollama (embedding de test)"}
    HC -->|échec| ALERT["Alerte + abandon<br/>(l'index de la veille reste servi)"]
    HC -->|OK| SYNC["Syncs incrémentales — l'extraction déterministe<br/>des relations (regex, JavaParser, parsing TS)<br/>se fait PAR DOCUMENT, pendant l'ingestion :<br/>Confluence (version.number) · code GitLab<br/>(diff SHA indexé → HEAD) · Jira · MRs en dernier<br/>(updated_after)"]
    SYNC --> PURGE["Purge des nœuds orphelins du graphe<br/>(les chunks obsolètes d'un document sont<br/>purgés à l'upsert, pendant la sync)"]
    PURGE --> VACUUM["VACUUM ANALYZE<br/>(index HNSW conservé, pas de rebuild)"]
    VACUUM --> ENRICH{"extractors.llm actif ?"}
    ENRICH -->|"oui"| LLME["Enrichissement LLM : nœuds TOPIC + alias<br/>pour les docs sans topic (plafond<br/>llm-max-docs-per-night), puis recâblage dé-hub,<br/>hygiène des topics et GC des topics non référencés"]
    ENRICH -->|"non — toute la branche est sautée"| SHEETS
    LLME --> SHEETS["Fiches projet régénérées<br/>(avec voisinage graphe)"]
    SHEETS --> QUAL["Éval qualité du graphe (verdict)<br/>+ éval recall@k (eval.cases)<br/>+ smoke test (questions canoniques)"]
    QUAL --> NOTIF["Notification ~02:45<br/>(NOTIFY_WEBHOOK_URL ou logs)"]
    NOTIF -.-> WARM(["07:30 — warm-up de l'embedding bge-m3 ;<br/>le LLM de chat reste chargé<br/>via OLLAMA_KEEP_ALIVE=24h"])
```

## 4. Résolution de l'authentification Atlassian

Cinq modes, résolus dans cet ordre depuis le `.env` (première condition vraie
retenue) — détail des variables : README « Authentification » et `.env.example`.

```mermaid
flowchart TD
    START["Variables .env de la source<br/>(CONFLUENCE_* ou JIRA_*)"] --> C1{"*_COOKIE ?"}
    C1 -->|oui| COOKIE["① cookie — session navigateur (SSO)<br/>dev/validation, expire avec la session"]
    C1 -->|non| C2{"*_OAUTH_CLIENT_ID<br/>+ *_OAUTH_CLIENT_SECRET ?"}
    C2 -->|oui| OAUTH["② oauth — client credentials Cloud<br/>recommandé en production<br/>(token auto-rafraîchi, api.atlassian.com)"]
    C2 -->|non| C3{"*_USER renseigné ?"}
    C3 -->|"oui (avec *_TOKEN<br/>dans l'usage nominal)"| BASIC["③ basic — compte de service Data Center<br/>ou API token classique Cloud"]
    C3 -->|non| C4{"*_TOKEN seul :<br/>site *.atlassian.net ?"}
    C4 -->|"oui (Cloud)"| SCOPED["④ scoped — token de compte de service<br/>(api.atlassian.com, cloudId auto-découvert)"]
    C4 -->|"non — self-hosted, ou aucune<br/>variable renseignée"| BEARER["⑤ bearer — PAT Data Center<br/>(défaut : échoue au 1er appel<br/>si aucun credential)"]
```

## 5. Topologie de déploiement — les trois modes Ollama

Le choix se fait entièrement dans `.env` (`OLLAMA_BASE_URL` + `COMPOSE_PROFILES`),
sans toucher au `docker-compose.yml` — permutation : RUNBOOK §4.

```mermaid
flowchart LR
    subgraph HOST["Poste hôte (Mac · Windows · WSL)"]
        subgraph COMPOSE["Docker Compose"]
            UI["open-webui :3000<br/>(profil ui, optionnel)"]
            API["rag-api :8080"]
            PG[("postgres + pgvector<br/>:5432")]
            OD["ollama :11434<br/>(profil ollama-docker,<br/>mode conteneur)"]
        end
        ON["Ollama natif hôte ou WSL<br/>:11434 (GPU — Metal / CUDA)"]
    end
    UI -->|"/v1 (OpenAI-compatible)"| API
    API --> PG
    API -->|"mode conteneur :<br/>OLLAMA_BASE_URL vide<br/>+ COMPOSE_PROFILES=ollama-docker"| OD
    API -.->|"mode natif/WSL :<br/>OLLAMA_BASE_URL=<br/>http://host.docker.internal:11434"| ON
```

## 6. Fraîcheur des données — temps réel GitLab vs J-1

```mermaid
sequenceDiagram
    participant GL as GitLab
    participant API as rag-api
    participant PG as Postgres
    Note over GL,PG: En journée — temps réel (webhook à configurer : RUNBOOK §8)
    GL->>API: POST /api/webhooks/gitlab (push · merge request)
    alt X-Gitlab-Token invalide
        API-->>GL: 403 Forbidden
    else token OK (ou GITLAB_WEBHOOK_TOKEN vide = non vérifié)
        API-->>GL: réponse immédiate — GitLab n'attend jamais l'indexation
        API->>PG: sync incrémentale en tâche de fond (upsert)
    end
    Note over GL,PG: La nuit — batch 02:00 (schéma 3)<br/>Confluence et Jira restent à J-1
```
