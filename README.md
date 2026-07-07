# x-rag-project-assistant

Assistant RAG d'équipe : posez des questions en langage naturel sur votre documentation
Confluence, votre code GitLab (Java/Spring Boot, TypeScript/Angular), l'historique des
Merge Requests et les issues Jira.

Exemples :

- « Explique-moi le projet Elog en 5 principes »
- « Comment faire communiquer Easy Loc et Epsilon ? »
- « Avons-nous eu un bug de persistance sur alpha ? »
- « Quelle MR ouverte est la plus vieille ? »

**Exportable** : toute équipe Confluence/Jira/GitLab déploie sa propre instance sans
toucher au code, uniquement via `team-config.yml` + `.env`.

## Architecture (résumé)

- **Backend** : Java 21, Spring Boot, Spring AI, architecture hexagonale (connecteurs =
  adapters, pipeline d'ingestion = domaine).
- **LLM** : Ollama + `qwen2.5:7b-instruct` par défaut (100 % local), commutable vers
  Gemini / endpoint OpenAI-compatible via profil Spring.
- **Embeddings** : `bge-m3` via Ollama (multilingue FR + code), toujours en local.
- **Stockage** : PostgreSQL 16 + pgvector. Le graphe de connaissances vit dans Postgres
  (`graph_nodes` / `graph_edges`), requêtes de voisinage en `WITH RECURSIVE`.
- **GraphRAG hybride** : détection d'entités par alias → expansion graphe (profondeur 2)
  → recherche vectorielle + full-text boostée par le sous-graphe.
- **Migrations** : Liquibase, exécutées au démarrage de l'API.

## Onboarding (< 1 h)

Prérequis : Docker + Docker Compose, ~16 Go RAM libres recommandés (inférence CPU).

1. **Cloner** le dépôt.
2. **Secrets** : `cp .env.example .env` puis renseigner mots de passe et tokens API.
   Les secrets vont dans `.env`, jamais dans le YAML.
3. **Configuration d'équipe** : `cp team-config.example.yml team-config.yml` puis
   déclarer vos espaces Confluence, groupe GitLab, projets Jira et alias.
4. **Démarrer** : `docker compose up -d` (ajouter `--profile ui` pour Open WebUI).
5. **Modèles** :
   `docker exec xrag-ollama ollama pull qwen2.5:7b-instruct` et
   `docker exec xrag-ollama ollama pull bge-m3`.
6. **Indexation initiale** : `./bootstrap.sh` (3 à 6 h la première nuit selon le volume).
7. **Vérifier** : le smoke test s'exécute en fin de bootstrap ; l'API répond sur
   `http://localhost:8080`, l'UI (si activée) sur `http://localhost:3000`.

Mises à jour : `docker compose pull && docker compose up -d` (Liquibase migre au démarrage).

## Services Docker Compose

| Service      | Rôle                                   | Port  |
|--------------|----------------------------------------|-------|
| `ollama`     | LLM + embeddings locaux                | 11434 |
| `postgres`   | pgvector + graphe + métadonnées        | 5432  |
| `rag-api`    | API RAG (Spring Boot)                  | 8080  |
| `open-webui` | UI de chat (optionnel, profil `ui`)    | 3000  |

## Performances attendues (CPU, Ryzen 7 / 32 Go)

| Type de question                  | Latence visée                      |
|-----------------------------------|------------------------------------|
| Factuel via tools (MRs)           | 10-15 s                            |
| Résumé projet (fiche pré-calculée)| 15-25 s complet, 1er token ~5 s    |
| Synthèse trans-projets A×B        | 45-90 s streamé                    |

Le batch nocturne (sync incrémentale, graphe, fiches projet) tourne à 02:00 et se
termine vers 02:45. En journée, les webhooks GitLab maintiennent code et MRs à jour ;
Confluence reste à J-1.

## Développement

```bash
mvn spring-boot:run   # nécessite Postgres + Ollama démarrés (docker compose up -d postgres ollama)
mvn verify            # build + tests
```

Voir `CLAUDE.md` pour le contexte projet complet et les décisions d'architecture.
