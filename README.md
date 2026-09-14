# x-rag-project-assistant

Assistant RAG d'équipe : posez des questions en langage naturel sur votre documentation
Confluence, votre code GitLab (Java/Spring Boot, TypeScript/Angular), l'historique des
Merge Requests et les issues Jira. Le code et les MRs arrivent par **l'un de deux
transports** : l'**API GitLab** (token), ou des **dépôts git locaux** montés en lecture
seule — sans aucun token API, pour les postes où la politique cyber l'interdit alors que
`git fetch` reste possible ; les MRs y sont alors **reconstituées depuis l'historique git**.

Exemples :

- « Explique-moi le projet fps-suite en 5 principes »
- « Comment faire communiquer FPS KDS et fps-pos ? »
- « Avons-nous eu un bug de persistance sur alpha ? »
- « Quelle MR ouverte est la plus vieille ? »

**Exportable** : toute équipe Confluence/Jira/GitLab déploie sa propre instance sans
toucher au code, uniquement via `team-config.yml` + `.env`.

## Documentation

Ce README est le point d'entrée ; le reste de la documentation est réparti par rôle :

| Document | Rôle | Quand le lire |
|---|---|---|
| 🗺️ **[WORKFLOWS.md](docs/WORKFLOWS.md)** | Les flux du système en **schémas Mermaid** : quel modèle répond, pipeline GraphRAG, batch nocturne, résolution d'auth, topologie Ollama, fraîcheur (webhooks GitLab ou fetch git local) | Pour comprendre le système en un coup d'œil |
| 📘 **[RUNBOOK.md](docs/RUNBOOK.md)** | Guide opérationnel pas à pas (🪟 Windows / 🐧 WSL) : installation, credentials (dont le **mode dépôts git locaux sans token GitLab**), démarrage, **accès aux services** (dashboard `dashboard.html`, endpoints admin, éval), exploitation courante, dépannage | Mise en service, puis exploitation quotidienne |
| ✅ **[VALIDATION.md](docs/VALIDATION.md)** | Checklist de mise en service : critères de succès **mesurables** (santé, indexation, qualité du graphe, latences vs cibles, batch nocturne, temps réel GitLab ou fetch git local) | Une fois l'onboarding ci-dessous terminé |
| 🧭 **[CLAUDE.md](CLAUDE.md)** | Contexte projet : décisions d'architecture validées, modèle de graphe, batch nocturne, exportabilité | Avant de contribuer au code |
| 📚 **[docs/](docs/README.md)** | Le reste de `docs/` : revue d'architecture, connaissance long-terme (lue par les agents BMAD — leurs artefacts vont dans `_bmad-output/`) | Approfondissement |

## Architecture (résumé)

- **Backend** : Java 21, Spring Boot, Spring AI, architecture hexagonale (connecteurs =
  adapters, pipeline d'ingestion = domaine).
- **LLM** : Ollama + `qwen2.5:7b-instruct` par défaut (100 % local), commutable vers
  Gemini (`llm.provider: gemini` dans `team-config.yml` + profil Spring `gemini`).
  Fallback optionnel `qwen2.5:3b` pour les questions descriptives simples
  (`llm.fallback-model`).
- **Embeddings** : `bge-m3` via Ollama (multilingue FR + code), toujours en local.
- **Stockage** : PostgreSQL 16 + pgvector. Le graphe de connaissances vit dans Postgres
  (`graph_nodes` / `graph_edges`), requêtes de voisinage en `WITH RECURSIVE`.
- **GraphRAG hybride** : détection d'entités par alias → expansion graphe (profondeur 2)
  → recherche vectorielle + full-text boostée par le sous-graphe.
- **Migrations** : Liquibase, exécutées au démarrage de l'API.
- **Code et MRs, deux transports exclusifs** (`sources.gitlab` **ou** `sources.git`,
  validé au démarrage) : l'**API GitLab** (découverte des repos du groupe, MRs par API,
  webhooks temps réel), ou des **dépôts git locaux** (`GIT_REPOS_DIR` monté sur `/repos`
  en lecture seule, lus via JGit — pur Java, aucun binaire git ni credential dans le
  conteneur ; MRs reconstituées depuis les commits de fusion, les séries de commits par
  clé Jira et les branches non fusionnées). Mêmes clés de chunk et même table MR dans les
  deux cas : basculer ne duplique rien. Détail et limites : RUNBOOK §3.

## Onboarding (< 1 h)

Prérequis : Docker + Docker Compose, ~16 Go RAM libres recommandés (inférence CPU).

1. **Cloner** le dépôt.
2. **Secrets** : `cp .env.example .env` puis renseigner mots de passe et tokens API.
   Les secrets vont dans `.env`, jamais dans le YAML. **Sans token GitLab** : laisser
   `GITLAB_TOKEN` vide et renseigner `GIT_REPOS_DIR` (chemin **absolu** Linux d'un dossier
   dédié de miroirs git, ex. `/home/<vous>/xrag-repos`, créés par
   `./scripts/git-mirror-init.sh repos.list` — RUNBOOK §3 « Alternative sans token »).
3. **Configuration d'équipe** : `cp team-config.example.yml team-config.yml` puis
   déclarer vos espaces Confluence, groupe GitLab, projets Jira et alias. En mode
   dépôts locaux : remplacer le bloc `sources.gitlab` par `sources.git` (les deux sont
   exclusifs — l'API refuse de démarrer si les deux sont présents).
4. **Démarrer** : `docker compose up -d`. Le mode Ollama (conteneur portable, natif
   hôte à GPU, ou WSL) et l'UI se choisissent dans `.env` via `OLLAMA_BASE_URL` et
   `COMPOSE_PROFILES` — voir `.env.example`. Défaut : Ollama en conteneur ; ajouter
   `ui` à `COMPOSE_PROFILES` pour Open WebUI.
5. **Modèles** :
   - Ollama en conteneur : `docker exec xrag-ollama ollama pull qwen2.5:7b-instruct`
     et `docker exec xrag-ollama ollama pull bge-m3`.
   - Ollama natif hôte / WSL : sur l'hôte, `ollama pull qwen2.5:7b-instruct` et
     `ollama pull bge-m3`.
   - Si `llm.fallback-model` est conservé dans `team-config.yml` (défaut de l'exemple),
     tirer aussi ce modèle (`ollama pull qwen2.5:3b`) — sinon les questions
     descriptives échouent (modèle absent), le préflight ne le vérifie pas.
6. **Préflight** : `./scripts/check-connections.sh` — teste Postgres, Ollama (+ modèles),
   GitLab, Confluence et Jira avec les credentials du `.env` et la même logique
   d'authentification que l'application. En mode dépôts locaux, la section GitLab API
   laisse place à une section « Git local » (`GIT_REPOS_DIR`, dépôts découverts, branches
   cibles, fraîcheur des fetch, montage `/repos`). Tout doit être vert avant d'indexer.
7. **Indexation initiale** : `./bootstrap.sh` (3 à 6 h la première nuit selon le volume).
8. **Vérifier** : le smoke test s'exécute en fin de bootstrap ; l'API répond sur
   `http://localhost:8080`, l'UI (si activée) sur `http://localhost:3000`, le dashboard
   d'indexation sur `http://localhost:8080/dashboard.html` (détail : RUNBOOK §7). En
   mode dépôts locaux : la source `gitlab-code` a des chunks, la source `gitlab-mr` liste
   des MRs reconstituées (état `merged` ou `opened`), aucune ligne en `ERREUR`.

Mises à jour : avec une image versionnée (`RAG_API_IMAGE` renseigné),
`docker compose pull && docker compose up -d` ; en build local (développement),
`git pull && docker compose up -d --build`. Liquibase migre au démarrage —
détail par type de changement : RUNBOOK §10.

### Authentification Confluence/Jira

Cinq modes, résolus depuis `.env` par ordre de priorité (voir `.env.example` pour le détail) :

1. **cookie** (`*_COOKIE`) : chaîne `Cookie` brute d'une session navigateur authentifiée
   (SSO, certificat SoftID…). Mode **dev/validation** (expire avec la session) et **seul
   mode possible pour du self-hosted** sans API token.
2. **oauth** (`*_OAUTH_CLIENT_ID` + `*_OAUTH_CLIENT_SECRET`) : OAuth 2.0 *client credentials*
   d'un compte de service Atlassian Cloud. **Recommandé en production** — aucune interaction
   humaine, access token rafraîchi automatiquement (~60 min). Appels routés par `api.atlassian.com`.
3. **basic** (`*_USER` + `*_TOKEN`) : compte de service Data Center, ou API token *classique*
   Atlassian Cloud (email + token).
4. **scoped** (`*_TOKEN` seul sur un site Cloud `*.atlassian.net`) : API token d'un compte de
   service. Appels routés par `api.atlassian.com`. Le token doit porter les scopes read
   Confluence **et** Jira ; laisser `*_USER` vide.
5. **bearer** (`*_TOKEN` seul sur du self-hosted) : PAT Data Center.

Les modes **oauth** et **scoped** découvrent le `cloudId` du tenant automatiquement
(`{site}/_edge/tenant_info`). Côté `team-config.yml`, le `base-url` d'un site Cloud est
`https://votre-org.atlassian.net/wiki` (Confluence) et `https://votre-org.atlassian.net`
(Jira) ; pour du self-hosted sous context path (`https://host/confluence`), l'inclure dans
le `base-url`. GitLab reste en PAT (`GITLAB_TOKEN`, scopes `read_api` + `read_repository`).

**Sans token GitLab : mode dépôts git locaux.** Si le token/session API GitLab est
interdit sur le poste mais que `git fetch` fonctionne (Git Credential Manager / SSO côté
Windows, ou clé SSH), remplacer `sources.gitlab` par `sources.git` dans `team-config.yml`
et pointer `GIT_REPOS_DIR` (`.env`) vers un dossier **dédié** de miroirs bare
(`git clone --mirror`, un par projet, `~/xrag-repos/<groupe>/<projet>.git`). Le conteneur
`rag-api` les lit en place, en lecture seule (`/repos:ro`), via JGit : **aucun appel API
GitLab, aucun `git fetch` depuis l'application, aucun credential dans le conteneur** —
la fraîcheur de l'index est celle de vos fetch (`scripts/git-fetch-all.sh --sync`,
à planifier). Les MRs sont reconstituées depuis l'historique (numéro, titre, branches,
auteur, fichiers touchés, clés Jira des commits) ; on y perd les commentaires de revue,
labels, approbations et MRs fermées sans fusion, et les MRs « ouvertes » sont les branches
distantes non fusionnées. Procédure complète, credentials WSL, planification et limites :
RUNBOOK §3 « Alternative sans token GitLab ».

**Lecture seule garantie** — même des credentials personnels avec droits d'écriture ne
peuvent pas altérer les plateformes :

1. **Audit du code** : les trois connecteurs n'émettent que des requêtes **GET**
   (recherche CQL, JQL, arborescences et fichiers git). Les seuls POST sortants sont la
   notification (`NOTIFY_WEBHOOK_URL`, envoyée **sans** les credentials des plateformes)
   et, en mode oauth, l'obtention du token sur `auth.atlassian.com` (client credentials).
   Les tools exposés au LLM (`listMergeRequests`, `searchMergeRequests`,
   `countMergeRequests`, `getMergeRequest`, `getIssue`, `searchKnowledgeBase`) lisent la
   base Postgres locale — le LLM n'a aucun outil vers les plateformes. Le connecteur
   « dépôts git locaux » n'émet **aucune** requête réseau : il lit l'object store git du
   volume `/repos` monté en lecture seule (`:ro`), sans checkout ni écriture.
2. **Garde-fou structurel** (`ReadOnlyHttpGuard`) : un intercepteur HTTP câblé dans les
   trois connecteurs **rejette toute requête non GET/HEAD avant qu'elle ne parte sur le
   réseau**. Même un bug futur ne peut pas produire d'écriture avec vos credentials.

Durcissements opérationnels (chmod du `.env`, cookies à exclure, scopes GitLab) :
docs/RUNBOOK.md, « Garanties lecture seule ».

## CI et images versionnées

- Chaque PR et chaque push sur `main` exécutent `mvn verify` (workflow `ci`).
- Chaque tag `vX.Y.Z` publie l'image `rag-api` versionnée (workflow `release`) :
  sur **GHCR** par défaut, ou sur un **registry privé** si les secrets
  `REGISTRY_URL` / `REGISTRY_USERNAME` / `REGISTRY_PASSWORD` sont définis dans le dépôt.
- Côté équipe : renseigner `RAG_API_IMAGE` dans `.env` avec l'image versionnée,
  puis `docker compose pull && docker compose up -d`. Sans `RAG_API_IMAGE`,
  le compose construit l'image localement (mode développement).

## Services Docker Compose

| Service      | Rôle                                                                     | Port  |
|--------------|--------------------------------------------------------------------------|-------|
| `ollama`     | LLM + embeddings locaux (profil `ollama-docker`, actif par défaut ; absent en mode Ollama natif/WSL) | 11434 |
| `postgres`   | pgvector + graphe + métadonnées                                          | 5432  |
| `rag-api`    | API RAG (Spring Boot). En mode dépôts locaux : `GIT_REPOS_DIR` monté sur `/repos` en lecture seule | 8080  |
| `open-webui` | UI de chat (optionnel, profil `ui`)                                      | 3000  |

## Performances attendues (CPU, Ryzen 7 / 32 Go)

| Type de question                  | Latence visée                      |
|-----------------------------------|------------------------------------|
| Factuel via tools (MRs)           | 10-15 s                            |
| Résumé projet (fiche pré-calculée)| 15-25 s complet, 1er token ~5 s    |
| Synthèse trans-projets A×B        | 45-90 s streamé                    |

Le batch nocturne (sync incrémentale, graphe, fiches projet) tourne à 02:00 et se
termine vers 02:45. En journée, les webhooks GitLab maintiennent code et MRs à jour
(mode API) — en mode dépôts git locaux, c'est `scripts/git-fetch-all.sh --sync`
(fetch des miroirs puis sync code + MRs, à planifier toutes les 2 h) qui joue ce rôle ;
Confluence reste à J-1.

## Développement

```bash
# Nécessite Postgres + Ollama démarrés (docker compose up -d postgres ollama).
# Exporter POSTGRES_PASSWORD (valeur du .env) — mais PAS POSTGRES_HOST=postgres :
# depuis l'hôte, l'app attend le défaut localhost.
export POSTGRES_PASSWORD=... && mvn spring-boot:run
mvn verify            # build + tests
```

Voir `CLAUDE.md` pour le contexte projet complet et les décisions d'architecture.
