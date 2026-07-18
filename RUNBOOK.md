# RUNBOOK — exploiter x-rag sur un poste Windows (Docker dans WSL)

Guide opérationnel pas à pas. Convention :

- 🪟 **Windows** : navigateur, explorateur de fichiers, PowerShell (uniquement quand indiqué)
- 🐧 **WSL Ubuntu** : toutes les commandes shell — ouvrez un terminal Ubuntu (`wsl` ou l'app Terminal)

Complémentaire de `README.md` (vue d'ensemble) et `VALIDATION.md` (critères de succès mesurables).

---

## 1. Prérequis (une seule fois)

| # | Où | Action |
|---|---|---|
| 1.1 | 🪟 | Installer **Docker Desktop**, puis Settings → Resources → **WSL Integration** → activer pour Ubuntu |
| 1.2 | 🪟 | Créer/éditer `C:\Users\<vous>\.wslconfig` : `[wsl2]` puis `memory=20GB` (inférence CPU 7B). Appliquer : PowerShell → `wsl --shutdown`, rouvrir Ubuntu |
| 1.3 | 🐧 | Vérifier l'outillage : `docker version && docker compose version && git --version && curl --version` |
| 1.4 | 🪟 | Avoir une session SSO fonctionnelle sur Confluence et Jira (SoftID), et un compte GitLab |

> **Ollama : trois hébergements possibles**, au choix dans `.env` — en **conteneur**
> (défaut, rien à installer, CPU), **natif Windows** (GPU) ou **WSL** (GPU NVIDIA via CUDA).
> Voir « Ollama : conteneur, natif Windows, ou WSL — et comment permuter » au §4. En mode
> conteneur, si l'app Ollama Windows est installée, **quittez-la** (barre des tâches) :
> sinon port 11434 et RAM en double.

## 2. Installation (une seule fois) — 🐧

```bash
cd ~                       # IMPORTANT : dans le FS WSL, jamais /mnt/c (I/O trop lentes)
git clone https://github.com/fatahbenguenna/x-rag-project-assistant.git
cd x-rag-project-assistant
cp .env.example .env
cp team-config.example.yml team-config.yml
nano team-config.yml       # base-url (context path inclus le cas échéant !), spaces,
                           # group GitLab, projects Jira, aliases
```

## 3. Récupérer les credentials

| Source | Où | Procédure | Destination `.env` (🐧 `nano .env`) |
|---|---|---|---|
| GitLab | 🪟 navigateur | Préférences → *Access Tokens* → scopes `read_api` + `read_repository` | `GITLAB_TOKEN=glpat-...` |
| Confluence | 🪟 navigateur | Se connecter (SSO), **F12** → Application → Cookies → copier **tous** les cookies du domaine (`confluence_cookie`, `JSESSIONID`, …) | `CONFLUENCE_COOKIE=confluence_cookie=...; JSESSIONID=...` |
| Jira | 🪟 navigateur | Idem — inclure **`seraph.rememberme.cookie`** (validité ~2 semaines) | `JIRA_COOKIE=INGRESSCOOKIE=...; JSESSIONID=...; seraph.rememberme.cookie=...` |
| Postgres | 🐧 | Choisir un mot de passe | `POSTGRES_PASSWORD=...` |

> Le mode cookie est un mode **dev/validation** : il expire avec la session (voir §8 pour le
> rafraîchissement). Cible pérenne selon la plateforme, sans autre changement que le `.env` :
> **Cloud** → OAuth 2.0 client credentials (`*_OAUTH_CLIENT_ID` + `*_OAUTH_CLIENT_SECRET`,
> recommandé) ou token de compte de service scopé (`*_TOKEN` seul, `*_USER` vide) ; **Data
> Center** → compte de service basic (`*_USER` + `*_TOKEN`) ou PAT (`*_TOKEN` seul). Les cinq
> modes sont détaillés dans `.env.example`. Ne jamais coller ces valeurs ailleurs que dans le `.env`.

### Garanties lecture seule (cookies personnels avec droits d'écriture)

L'application **ne peut pas** altérer les données de Confluence, Jira ou GitLab, même avec
des credentials qui auraient tous les droits :

1. **Audit du code** : les trois connecteurs n'émettent que des requêtes **GET**
   (recherche CQL, JQL, arborescences et fichiers git). Le seul POST sortant de
   l'application est la notification (`NOTIFY_WEBHOOK_URL`), envoyée **sans** les
   credentials des plateformes. Les tools exposés au LLM (`listMergeRequests`,
   `searchMergeRequests`, `countMergeRequests`) lisent la base locale — le LLM n'a
   aucun outil vers les plateformes.
2. **Garde-fou structurel** (`ReadOnlyHttpGuard`) : un intercepteur HTTP câblé dans les
   trois connecteurs **rejette toute requête non GET/HEAD avant qu'elle ne parte sur le
   réseau**. Même un bug ou une régression future ne peut pas produire d'écriture avec
   vos credentials.
3. **Durcissements recommandés** :
   - `chmod 600 .env` (lisible par vous seul) ;
   - ne **pas** inclure `atlassian.xsrf.token` dans `JIRA_COOKIE` : inutile en lecture,
     c'est le jeton qui faciliterait des écritures « type navigateur » si les cookies
     fuitaient ;
   - GitLab : un PAT aux scopes `read_api` + `read_repository` est incapable d'écrire
     **par construction** (contrôle côté serveur) ;
   - les seuls scripts du dépôt qui écrivent sur les plateformes sont ceux du testbed
     (`testbed/setup-*.sh`) : exécution manuelle uniquement, jamais appelés par
     l'application — ne les lancez jamais avec un groupe/space/projet réel en paramètre.

## 4. Démarrer la pile — 🐧

```bash
# Premier lancement : les images publiques se téléchargent d'abord (~3,5 Go, dont
# 3,3 Go pour ollama — laisser finir), puis --build construit l'image rag-api
# localement (étape Maven : 5-15 min la première fois, quelques secondes ensuite
# grâce au cache Docker).
# L'avertissement « pull access denied for xrag/rag-api » est NORMAL en mode dev :
# cette image se construit chez vous (build: .), elle n'existe sur aucun registry.
docker compose up -d --build               # postgres + rag-api (+ ollama et/ou webui selon COMPOSE_PROFILES)
# Le mode Ollama et l'UI se pilotent dans .env (OLLAMA_BASE_URL, COMPOSE_PROFILES) — voir
# .env.example : Ollama en conteneur (COMPOSE_PROFILES=ollama-docker), natif hôte à GPU, ou WSL
# (OLLAMA_BASE_URL=http://host.docker.internal:11434). Ajouter « ui » pour Open WebUI.

# Attendu AVANT de continuer :
docker compose ps
#   xrag-postgres   Up (healthy)
#   xrag-ollama     Up               (seulement en mode conteneur : COMPOSE_PROFILES=ollama-docker)
#   xrag-api        Up
#   xrag-webui      Up               (seulement si COMPOSE_PROFILES contient « ui »)

# Puis télécharger les modèles (~5 Go, une seule fois). EN MODE CONTENEUR :
docker exec xrag-ollama ollama pull qwen2.5:7b-instruct
docker exec xrag-ollama ollama pull bge-m3
# En mode Ollama natif Windows / WSL : les MÊMES pulls SANS « docker exec xrag-ollama »,
# directement sur l'hôte (voir la section « Ollama : conteneur, natif Windows, ou WSL »).
```

Enchaîner directement sur le préflight (§5) avant toute indexation.

### Ollama : conteneur, natif Windows, ou WSL — et comment permuter

Le RAG appelle Ollama via `OLLAMA_BASE_URL` (`.env`). Le choix de l'hébergement se fait
**entièrement dans `.env`**, sans éditer `docker-compose.yml`.

| Mode | Quand | `.env` | Où lancer Ollama / puller les modèles |
|---|---|---|---|
| **Conteneur** | Portable, pas de GPU | `COMPOSE_PROFILES=ollama-docker` · `OLLAMA_BASE_URL=` (vide) | Rien à installer. `docker exec xrag-ollama ollama pull …` |
| **Natif Windows** | GPU sur Windows | `COMPOSE_PROFILES=` (sans `ollama-docker`) · `OLLAMA_BASE_URL=http://host.docker.internal:11434` | 🪟 Installer Ollama for Windows · `setx OLLAMA_HOST 0.0.0.0` puis relancer Ollama · `ollama pull …` (PowerShell) |
| **WSL** | GPU NVIDIA (CUDA) dans WSL | idem natif : `COMPOSE_PROFILES=` · `OLLAMA_BASE_URL=http://host.docker.internal:11434` | 🐧 `curl -fsSL https://ollama.com/install.sh \| sh` · lancer avec `OLLAMA_HOST=0.0.0.0 ollama serve` · `ollama pull …` (WSL) |

**Natif Windows et WSL partagent le même `.env`** (`host.docker.internal:11434`) : vus depuis
le conteneur rag-api, les deux sont « l'hôte ». Un seul peut occuper le port 11434 à la fois.

**Permuter natif Windows ↔ WSL** (le `.env` ne change pas) :
1. Arrêter l'Ollama en cours (🪟 quitter l'app Ollama / 🐧 `pkill ollama`) — libère le port 11434.
2. Démarrer l'Ollama voulu **en écoutant `0.0.0.0`** (sinon injoignable depuis le conteneur).
3. `docker compose restart rag-api`.
4. Vérifier : `./scripts/check-connections.sh` (ligne Ollama verte).

**Permuter conteneur ↔ natif/WSL** : éditer `.env` (`COMPOSE_PROFILES` + `OLLAMA_BASE_URL`),
puis `docker compose up -d` (ajoute/retire le conteneur `ollama` et recrée `rag-api`).

> Si `host.docker.internal:11434` ne joint pas l'Ollama WSL (rare) : 🐧 `hostname -I` →
> `OLLAMA_BASE_URL=http://<ip-wsl>:11434` (attention, l'IP de la distro peut changer au reboot).

> **Open WebUI est optionnel** : le RAG fonctionne entièrement par l'API (`:8080`). L'UI
> (`http://localhost:3000`) est l'interface type ChatGPT pour les utilisateurs humains,
> branchée sur l'endpoint `/v1`. On peut l'ajouter à tout moment en relançant la commande
> avec `--profile ui` — les autres conteneurs ne sont pas touchés.

> **Cookies par source** : `CONFLUENCE_COOKIE` reçoit les cookies du domaine Confluence,
> `JIRA_COOKIE` ceux du domaine Jira — chaque serveur a son propre `JSESSIONID`, ne pas
> les mélanger. Copier la totalité des cookies du domaine : un de trop ne gêne jamais.

## 5. Préflight — 🐧

```bash
./scripts/check-connections.sh
```

Teste Postgres, Ollama (+ modèles), GitLab, Confluence, Jira avec les credentials du `.env`
et la même logique d'authentification que l'application. **Tout doit être vert** avant
d'indexer — sinon voir §9.

## 6. Indexation initiale — 🐧

```bash
./bootstrap.sh             # 3 à 6 h selon le volume — lancer en fin de journée
```

Suivi pendant l'indexation :
- **Dashboard visuel** (recommandé) : `http://localhost:8080/dashboard.html` — chunks par
  source, source en cours, temps écoulé, problèmes rencontrés et dernière sync par source,
  auto-rafraîchi toutes les 3 s.
- En ligne de commande : `curl -s localhost:8080/api/admin/status` (compteurs bruts) ou
  `curl -s localhost:8080/api/admin/indexing-status` (statut détaillé JSON servant le dashboard).

Interruptible sans risque : tout est en upsert à clés stables, relancer reprend
où c'était. Le smoke test s'exécute automatiquement à la fin.

## 7. Accéder au service

| Quoi | Où | Comment |
|---|---|---|
| **Chat (UI)** | 🪟 navigateur | `http://localhost:3000` (si `--profile ui`) — modèle `xrag-<team>` ; localhost est partagé Windows↔WSL, rien à configurer |
| Chat (CLI, streamé) | 🐧 | `curl -N -X POST localhost:8080/api/chat -H 'Content-Type: application/json' -d '{"question":"explique-moi le projet X"}'` |
| Santé | 🪟/🐧 | `http://localhost:8080/actuator/health` → `{"status":"UP"}` |
| **Dashboard d'indexation** | 🪟 navigateur | `http://localhost:8080/dashboard.html` — monitoring temps quasi-réel (chunks/source, tâche en cours, temps écoulé, problèmes) |
| État de l'index | 🐧 | `curl -s localhost:8080/api/admin/status` (brut) ou `.../api/admin/indexing-status` (détaillé) |
| Qualité du graphe | 🐧 | `curl -s localhost:8080/api/admin/graph-quality` (verdict + trous éventuels) |
| Enrichissement LLM du graphe | 🐧 | `curl -X POST 'localhost:8080/api/admin/enrich?max=150'` (async, bilan dans les logs) — utile si `graph-quality` signale < 50 % de chunks rattachés ; sinon automatique dans le batch nocturne si `extractors.llm: true` |
| Batch à la demande | 🐧 | `curl -X POST localhost:8080/api/admin/nightly` |
| Latences vs cibles | 🐧 | `./scripts/measure-latency.sh` |

## 8. Exploitation courante

- **Batch nocturne à 02:00** : automatique **si le poste et WSL tournent à cette heure**.
  Poste éteint la nuit → lancer le rattrapage le matin : `curl -X POST localhost:8080/api/admin/nightly`.
- **Notifications** : renseigner `NOTIFY_WEBHOOK_URL` dans `.env` (webhook Slack/Mattermost/
  Rocket.Chat) — sinon les alertes ne sont que dans les logs.
- **Rafraîchir les cookies** (mode cookie, au premier 401 / alerte health check) :
  1. 🪟 se reconnecter à Confluence/Jira, recopier les cookies (F12) ;
  2. 🐧 les mettre à jour dans `.env`, puis `docker compose up -d rag-api` (recrée le conteneur avec le nouvel environnement).
- **Logs** : `docker compose logs -f rag-api` (ou `postgres`, `ollama`).
- **Arrêt / redémarrage** : `docker compose down` puis `up -d` — les données (index, modèles)
  vivent dans des volumes Docker et sont préservées.

## 9. Dépannage

| Symptôme | Cause probable | Remède |
|---|---|---|
| Build : `Network is unreachable` sur `repo.maven.apache.org` | Proxy/miroir d'entreprise — le conteneur de build Maven n'a pas d'accès direct (la JVM ignore `HTTP_PROXY`) | **Le plus simple** si Maven marche déjà côté 🪟 grâce à un `settings.xml` : `mkdir -p ~/.m2 && cp /mnt/c/Users/<vous>/.m2/settings.xml ~/.m2/` puis `MAVEN_SETTINGS=/home/<vous>/.m2/settings.xml` dans `.env` (secret BuildKit, jamais dans l'image). Sinon : `BUILD_NETWORK=host` (si `curl -sI https://repo.maven.apache.org/maven2/` répond depuis 🐧) ou `MAVEN_OPTS=-Dhttps.proxyHost=... -Dhttps.proxyPort=...`. Puis `docker compose build rag-api` |
| Préflight `[KO] ... HTTP 401` | Cookie expiré ou token invalide | §8 rafraîchissement des cookies |
| Préflight `[KO] ... HTTP 404` | Context path absent du `base-url`, ou clé espace/projet erronée | Corriger `team-config.yml` (ex. `https://host/confluence`) |
| Préflight `200 mais contenu inattendu (HTML)` | Redirection vers la mire SSO | Cookies incomplets — recopier **tous** les cookies du domaine |
| `ollama pull` : `i/o timeout` vers `registry.ollama.ai` | Proxy d'entreprise — le conteneur ne peut pas sortir en direct | `PROXY_URL=http://proxy...:port` dans `.env` (même hôte/port que le settings.xml Maven), puis `docker compose up -d ollama` et relancer le pull |
| `ollama pull` : « something went wrong » | Voir la vraie erreur : `docker logs xrag-ollama --tail 30` | `x509: unknown authority` → interception TLS : `CORPORATE_CA=/chemin/ca-entreprise.pem` (export 🪟 `certmgr.msc` en Base64) puis `docker compose up -d ollama` · `407` → `PROXY_URL=http://login:mdp@proxy...` · `403` → demander la liste blanche de `registry.ollama.ai` |
| Ollama injoignable (`UnknownHostException` / connexion refusée) | Le mode `.env` ne correspond pas à l'Ollama réellement lancé | **Mode conteneur** : `docker compose up -d ollama` (profil `ollama-docker` actif). **Mode natif/WSL** : démarrer Ollama sur l'hôte et le faire écouter `0.0.0.0` (`OLLAMA_HOST=0.0.0.0` puis relancer Ollama), avec `OLLAMA_BASE_URL=http://host.docker.internal:11434`. Ne pas mélanger les deux (conflit de port 11434) |
| Conteneur `ollama` tué / OOM | Limite mémoire WSL2 | §1.2 `.wslconfig` `memory=20GB`, `wsl --shutdown` |
| Indexation très lente, I/O saturées | Dépôt cloné sous `/mnt/c` | Recloner dans `~` (FS ext4 de WSL) |
| Compteurs `status` figés à 0 | Connexion source en échec en cours de route | `docker compose logs rag-api`, re-préflight |
| 1er token > 30 s après une pause | Modèle déchargé de la RAM | `OLLAMA_KEEP_ALIVE=24h` (défaut du compose) + warm-up 07:30 du batch |
| Pas de batch cette nuit | Poste/WSL éteint à 02:00 | Rattrapage manuel (§8) |
| `curl : option -u inconnue` | Vous êtes dans PowerShell (alias `Invoke-WebRequest`) | Utiliser le terminal 🐧 WSL, ou `curl.exe` |

## 10. Mise à jour de l'instance — 🐧

L'image Docker est une photo du code au moment du build : modifier le code sur le disque
ne change rien au conteneur qui tourne. Selon ce qui a changé :

| Changement | Commande (seul `rag-api` est touché ; données et modèles préservés) |
|---|---|
| **Code** (`git pull`) | `docker compose build rag-api && docker compose up -d rag-api` |
| **`.env`** (cookies, tokens) | `docker compose up -d rag-api` (recréation — pas de rebuild) |
| **`team-config.yml`** | `docker compose restart rag-api` (monté en volume, relu au démarrage) |

```bash
# En développement (build local) :
git pull && docker compose build rag-api && docker compose up -d

# En mode image versionnée (RAG_API_IMAGE dans .env) :
docker compose pull && docker compose up -d    # Liquibase migre au démarrage
```

Jamais de perte d'index lors d'une mise à jour : le schéma évolue par migrations, les
données restent dans les volumes.
