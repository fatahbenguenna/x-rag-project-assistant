# x-rag-project-assistant

Assistant RAG d'équipe : posez des questions en langage naturel sur votre documentation **Confluence**, votre code **GitLab** (Java/Spring Boot, TypeScript/Angular), l'historique des **Merge Requests** et les issues **Jira**.

Exemples :

- « Explique-moi le projet Elog en 5 principes »
- « Comment faire communiquer Easy Loc et Epsilon ? »
- « Avons-nous eu un bug de persistance sur alpha ? »
- « Quelle MR ouverte est la plus vieille ? »

**Produit exportable** : chaque équipe déploie sa propre instance (Docker Compose autonome) sans toucher au code — uniquement via `team-config.yml` et `.env`.

## Architecture (résumé)

- **LLM** : Ollama + `qwen2.5:7b-instruct` (Q4) par défaut, 100 % local. Commutable via `ChatClient` Spring AI : profil `ollama` ou `gemini`/endpoint OpenAI-compatible.
- **Embeddings** : `bge-m3` via Ollama (multilingue FR + code), toujours local.
- **Vector store + graphe** : PostgreSQL 16 + pgvector. Le graphe de connaissances (projets, pages, MRs, classes, tables, topics…) vit dans Postgres (`graph_nodes` / `graph_edges`), requêtes de voisinage en `WITH RECURSIVE`.
- **GraphRAG hybride** : détection d'entités par alias → expansion graphe profondeur 2 → sous-graphe injecté au prompt → recherche vectorielle + full-text boostée par le graphe.
- **Tools (function calling)** pour les questions factuelles (MRs ouvertes, tris, comptages).
- **Batch nocturne** : sync incrémentale des sources, extraction de relations, fiches projet pré-calculées, smoke tests. En journée, webhooks GitLab en temps réel.
- **Migrations** : Liquibase, exécutées au démarrage de `rag-api`.

## Onboarding (< 1 h de configuration)

Prérequis : Docker + Docker Compose, ~10 Go de disque (modèles Ollama), 16 Go de RAM recommandés (référence : Ryzen 7, 32 Go, inférence CPU).

```bash
# 1. Cloner
git clone <url-du-repo> && cd x-rag-project-assistant

# 2. Secrets
cp .env.example .env          # renseigner POSTGRES_PASSWORD + tokens Confluence/GitLab/Jira

# 3. Configuration d'équipe
cp team-config.example.yml team-config.yml   # spaces Confluence, groupe GitLab, projets Jira, alias…

# 4. Démarrer
docker compose up -d          # ajouter --profile ui pour Open WebUI

# 5. Indexation initiale (3-6 h la première nuit)
./bootstrap.sh

# 6. Vérifier
# le smoke test rejoue les questions canoniques définies dans team-config.yml
```

Mise à jour : `docker compose pull && docker compose up -d` (Liquibase migre automatiquement au démarrage).

> `team-config.example.yml` et `bootstrap.sh` arrivent dans les étapes suivantes du plan de PRs (voir CLAUDE.md).

## Services Compose

| Service | Rôle | Port |
|---|---|---|
| `postgres` | PostgreSQL 16 + pgvector (chunks, graphe, métadonnées MR) | interne |
| `ollama` | LLM + embeddings locaux | interne |
| `rag-api` | Backend Spring Boot (ingestion, retrieval, chat streamé) | 8080 |
| `open-webui` | UI de chat (optionnel, profil `ui`) | 3000 |

## Développement

```bash
mvn spring-boot:run        # nécessite Java 21
mvn test
```

Structure hexagonale : `domain` (modèle + ports) / `application` (cas d'usage) / `adapter.in` (REST, scheduler, webhooks) / `adapter.out` (Confluence, GitLab, Jira, Postgres, LLM) / `infrastructure` (config).

## Performance attendue (CPU, 7B Q4)

| Type de question | Latence visée |
|---|---|
| Factuel via tools (MRs) | 10-15 s |
| Résumé projet (fiche pré-calculée) | 15-25 s complet, 1er token ~5 s |
| Synthèse trans-projets A×B | 45-90 s streamé |
