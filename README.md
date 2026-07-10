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
6. **Préflight** : `./scripts/check-connections.sh` — teste Postgres, Ollama (+ modèles),
   GitLab, Confluence et Jira avec les credentials du `.env` et la même logique
   d'authentification que l'application. Tout doit être vert avant d'indexer.
7. **Indexation initiale** : `./bootstrap.sh` (3 à 6 h la première nuit selon le volume).
8. **Vérifier** : le smoke test s'exécute en fin de bootstrap ; l'API répond sur
   `http://localhost:8080`, l'UI (si activée) sur `http://localhost:3000`.

Mises à jour : `docker compose pull && docker compose up -d` (Liquibase migre au démarrage).

### Windows : toute la chaîne s'exécute dans WSL (Ubuntu)

Docker tourne dans WSL — travaillez depuis un terminal **WSL Ubuntu**, jamais depuis
PowerShell (où `curl` est un alias d'`Invoke-WebRequest` ; à défaut, utiliser `curl.exe`).

```bash
# 0. Prérequis (une fois) : Docker Desktop avec intégration WSL activée pour Ubuntu,
#    ou docker-ce natif dans WSL2. Vérifier :
docker version && docker compose version

# 1. Cloner DANS le système de fichiers WSL (~/), PAS dans /mnt/c
#    (I/O beaucoup plus lentes sur /mnt/c — critique pour Postgres et les embeddings)
cd ~ && git clone https://github.com/fatahbenguenna/x-rag-project-assistant.git
cd x-rag-project-assistant

# 2. Secrets et configuration
cp .env.example .env && nano .env                          # tokens/cookies
cp team-config.example.yml team-config.yml && nano team-config.yml

# 3. Démarrer la pile et tirer les modèles
docker compose up -d
docker exec xrag-ollama ollama pull qwen2.5:7b-instruct
docker exec xrag-ollama ollama pull qwen2.5:3b
docker exec xrag-ollama ollama pull bge-m3

# 4. Préflight : toutes les connexions vertes avant d'indexer
./scripts/check-connections.sh

# 5. Indexation initiale (3-6 h selon le volume), puis questions
./bootstrap.sh
```

Notes WSL :

- **Ollama : rien à installer** — ni sous Windows ni sous WSL : le compose embarque son
  propre Ollama en conteneur (`xrag-ollama`), avec ses modèles dans un volume Docker.
  Si l'application Ollama **Windows** tourne déjà, quittez-la pendant l'utilisation de la
  pile : elle occupe le port 11434 côté Windows (confusion possible en déboguant depuis
  le navigateur) et consommerait de la RAM en double si un modèle y est chargé.
- **RAM** : WSL2 se limite par défaut à ~50 % de la RAM. Pour l'inférence CPU du 7B,
  allouer au moins 20 Go dans `C:\Users\<vous>\.wslconfig` (`[wsl2]` puis `memory=20GB`),
  puis `wsl --shutdown` pour appliquer.
- **Accès depuis Windows** : localhost est partagé — API sur `http://localhost:8080`,
  Open WebUI sur `http://localhost:3000` depuis le navigateur Windows.
- **Cookies SSO** : copiés depuis DevTools du navigateur Windows vers le `.env` de WSL —
  c'est du texte, aucune friction Windows/WSL.

### Authentification Confluence/Jira

Trois modes, résolus depuis `.env` par ordre de priorité (voir `.env.example`) :

1. **cookie** (`CONFLUENCE_COOKIE` / `JIRA_COOKIE`) : chaîne `Cookie` brute copiée d'une
   session navigateur authentifiée (SSO, certificat SoftID…). Mode **dev/validation** —
   expire avec la session ; le health check du batch signale l'expiration.
2. **basic** (`CONFLUENCE_USER` + `CONFLUENCE_TOKEN`, idem Jira) : compte de service
   Data Center, ou Atlassian Cloud (email + API token).
3. **bearer** (`CONFLUENCE_TOKEN` seul, défaut) : PAT Data Center.

Si l'instance est servie sous un context path (`https://host/confluence`), l'inclure
dans le `base-url` du `team-config.yml`. GitLab reste en PAT (`GITLAB_TOKEN`).

## CI et images versionnées

- Chaque PR et chaque push sur `main` exécutent `mvn verify` (workflow `ci`).
- Chaque tag `vX.Y.Z` publie l'image `rag-api` versionnée (workflow `release`) :
  sur **GHCR** par défaut, ou sur un **registry privé** si les secrets
  `REGISTRY_URL` / `REGISTRY_USERNAME` / `REGISTRY_PASSWORD` sont définis dans le dépôt.
- Côté équipe : renseigner `RAG_API_IMAGE` dans `.env` avec l'image versionnée,
  puis `docker compose pull && docker compose up -d`. Sans `RAG_API_IMAGE`,
  le compose construit l'image localement (mode développement).

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
