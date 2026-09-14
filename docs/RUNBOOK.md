# RUNBOOK — exploiter x-rag sur un poste Windows (Docker dans WSL)

Guide opérationnel pas à pas. Convention :

- 🪟 **Windows** : navigateur, explorateur de fichiers, PowerShell (uniquement quand indiqué)
- 🐧 **WSL Ubuntu** : toutes les commandes shell — ouvrez un terminal Ubuntu (`wsl` ou l'app Terminal)

Complémentaire du `README.md` (vue d'ensemble, racine du dépôt) et de `VALIDATION.md`
(critères de succès mesurables, même dossier).

---

## 1. Prérequis (une seule fois)

| # | Où | Action |
|---|---|---|
| 1.1 | 🪟 | Installer **Docker Desktop**, puis Settings → Resources → **WSL Integration** → activer pour Ubuntu |
| 1.2 | 🪟 | Créer/éditer `C:\Users\<vous>\.wslconfig` : `[wsl2]` puis `memory=20GB` (inférence CPU 7B). Appliquer : PowerShell → `wsl --shutdown`, rouvrir Ubuntu |
| 1.3 | 🐧 | Vérifier l'outillage : `docker version && docker compose version && git --version && curl --version` |
| 1.4 | 🪟 | Avoir une session SSO fonctionnelle sur Confluence et Jira (SoftID), et un compte GitLab |

> **Ollama : trois hébergements possibles**, au choix dans `.env` — voir le tableau et
> les procédures de permutation au §4. En mode conteneur (défaut), si l'app Ollama
> Windows est installée, **quittez-la** (barre des tâches) : elle tient le port 11434 et
> le conteneur `xrag-ollama` ne pourrait pas publier le sien (`docker compose up`
> échouerait pour ce service), en plus de la RAM en double.

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
| GitLab | 🪟 navigateur | Préférences → *Access Tokens* → scopes `read_api` + `read_repository`. **Token interdit sur le poste ?** → sous-section « Alternative sans token GitLab » ci-dessous (`GITLAB_TOKEN` reste vide) | `GITLAB_TOKEN=glpat-...` |
| Confluence | 🪟 navigateur | Se connecter (SSO), **F12** → Application → Cookies → copier **tous** les cookies du domaine (`confluence_cookie`, `JSESSIONID`, …) | `CONFLUENCE_COOKIE=confluence_cookie=...; JSESSIONID=...` |
| Jira | 🪟 navigateur | Idem — inclure **`seraph.rememberme.cookie`** (validité ~2 semaines) | `JIRA_COOKIE=INGRESSCOOKIE=...; JSESSIONID=...; seraph.rememberme.cookie=...` |
| Postgres | 🐧 | Choisir un mot de passe | `POSTGRES_PASSWORD=...` |

> Le mode cookie est un mode **dev/validation** : il expire avec la session (voir §8 pour
> le rafraîchissement). Pour les modes pérennes (OAuth, scoped, basic, PAT — seul le `.env`
> change) : README « Authentification Confluence/Jira » et `.env.example`. Ne jamais coller
> ces valeurs ailleurs que dans le `.env`.

### Alternative sans token GitLab : dépôts git locaux (`sources.git`)

Pour les postes où la politique cyber **interdit le token/session API GitLab** mais où
`git fetch` fonctionne (Git Credential Manager / SSO côté Windows, ou clé SSH). L'API lit
alors des **dépôts git locaux** montés en lecture seule dans le conteneur (`/repos:ro`),
via JGit (pur Java — aucun binaire git, aucun credential dans l'image) : le code est lu
dans l'object store des branches cibles (sans checkout), les MRs sont **reconstituées
depuis l'historique** (commits de fusion GitLab « See merge request grp/proj!153 »,
commits de squash « Titre (grp/proj!153) », séries de commits directs partageant une clé
Jira, branches distantes non fusionnées = MRs ouvertes présumées). **L'application ne
fait jamais de `git fetch`** : la fraîcheur de l'index est celle de vos fetch (§8).

**Standard recommandé : miroirs bare dans un dossier dédié du FS WSL** — 🐧

```bash
# 1. Lister les URLs de clone (une par ligne, commentaires #) — modèle : repos.list.example
cp repos.list.example repos.list && nano repos.list
# 2. Créer les miroirs : ~/xrag-repos/<groupe>/<projet>.git (chemin déduit de l'URL),
#    idempotent (existant = ignoré), gc.autoDetach=false posé sur chaque miroir
./scripts/git-mirror-init.sh repos.list ~/xrag-repos
# 3. .env : chemin ABSOLU Linux du dossier (monté par docker-compose sur /repos:ro)
GIT_REPOS_DIR=/home/<vous>/xrag-repos
```

Puis dans `team-config.yml`, **remplacer** le bloc `sources.gitlab` par `sources.git`
(les deux sont exclusifs : l'API refuse de démarrer si les deux sont présents) :

```yaml
sources:
  git:
    root: /repos                    # dossier DANS le conteneur (= GIT_REPOS_DIR monté)
    repos: []                       # vide = découverte auto (profondeur 4 : de root/projet à
                                    # root/grp/sous-grp/sous-sous-grp/projet) ; en prod, lister les sous-chemins
    branches: [main, develop]       # code indexé + historique fouillé pour les MRs
    remote: origin                  # refs/remotes/origin/<b> lues en priorité, repli refs/heads/<b>
    # web-base-url: https://gitlab.example.com   # liens cliquables ; défaut : déduite du remote
    merge-requests:
      from-merge-commits: true      # MRs fusionnées (commits de fusion et squash référencés)
      from-linear-commits: true     # séries de commits directs par clé Jira (fast-forward)
      from-open-branches: true      # branches non fusionnées = MRs ouvertes présumées
      # jira-projects: []           # défaut : sources.jira.projects (détection insensible à la casse)
```

Règles à respecter, chacune ayant une raison technique :

| Règle | Pourquoi |
|---|---|
| Dossier **dédié** (`~/xrag-repos`), jamais `/`, `/home`, `/mnt`, `/tmp`… | L'API refuse ces racines au démarrage de la sync : elles exposeraient tout le système de fichiers au conteneur (et au chat) |
| Dans le **FS WSL ext4**, jamais `/mnt/c` | I/O 9P trop lentes pour parcourir des object stores ; même règle que pour le clone du projet (§2) |
| **Miroirs bare** (`git clone --mirror`), pas vos clones de travail | Un clone de travail a un worktree possiblement sale et des refs locales en retard ; un `.git` **fichier** (worktree lié, sous-module) pointe vers un chemin hôte invisible dans le conteneur → ignoré avec un WARN |
| Pas de **symlinks** dans le dossier | Ignorés à la découverte : un lien hôte ne résout pas dans le conteneur, et un lien sortant élargirait le périmètre indexé |
| Pas de clone **partiel** (`--filter`) ni **shallow** (`--depth`) | Partiel = erreur (JGit ne peut pas récupérer les objets manquants) ; shallow = WARN, historique tronqué, MRs anciennes irrécupérables (`git fetch --unshallow`) |
| `GIT_REPOS_DIR` **absolu** Linux | Monté tel quel par docker-compose (`${GIT_REPOS_DIR:-./repos}:/repos:ro`) ; un `~` ou un chemin Windows ne résout pas |
| L'URL du remote `origin` doit être celle de la forge | Le **slug** du projet (résolu par vos `aliases`) est le dernier segment du chemin distant (`grp/sub/proj.git` → `proj`) ; sans remote lisible, repli sur le nom du dossier (WARN). Les URLs remote ne sont jamais loggées (elles peuvent porter un token) |

**Credentials de `git fetch` dans WSL, sans token stocké** — 🐧 :

```bash
# Réutiliser le coffre Windows / SSO via Git Credential Manager (installé avec Git for Windows)
git config --global credential.helper "/mnt/c/Program\ Files/Git/mingw64/bin/git-credential-manager.exe"
# — ou une clé SSH (URLs git@… dans repos.list). Proxy d'entreprise, le cas échéant :
git config --global http.proxy "$PROXY_URL"
git config --global http.sslCAInfo "$CORPORATE_CA"
# Test rapide, avant de créer les miroirs :
git ls-remote --heads <url-du-projet>
```

**Ce que ce mode ne donne pas** (à connaître avant de choisir) : ni commentaires de
revue, ni labels, ni approbations, ni MRs fermées sans fusion (invisibles dans git — les
états sont `merged` ou `opened`, jamais `closed`) ; les MRs ouvertes sont une
**heuristique** « branche distante non fusionnée » (une branche poussée sans MR apparaît ;
une branche squash-mergée sans commit de fusion et non supprimée peut rester « ouverte ») ;
fidélité maximale avec la stratégie GitLab **« merge commit »** (template par défaut),
partielle en fast-forward — en squash, configurer le *squash commit template*
`%{title} (%{reference})` + description pour conserver le numéro de MR ; les MRs sans
numéro sont citées par leur id (`git:<slug>:commits:<sha>`, `git-branch:<slug>:<branche>`)
et ne se cherchent pas par `!n` ; plus de découverte automatique des projets du groupe :
`repos.list` est à maintenir ; pas de webhook : fraîcheur = fréquence des fetch (§8).

### Garanties lecture seule (cookies personnels avec droits d'écriture)

L'application **ne peut pas** altérer les données de Confluence, Jira ou GitLab, même avec
des credentials qui auraient tous les droits : connecteurs GET-only + garde-fou structurel
`ReadOnlyHttpGuard` — le détail de ces deux garanties produit est au README, section
« Authentification Confluence/Jira ». Durcissements opérationnels recommandés :

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
# Si llm.fallback-model est conservé dans team-config.yml (défaut de l'exemple) :
docker exec xrag-ollama ollama pull qwen2.5:3b
# — sinon les questions descriptives échoueront (« model not found »), et le préflight
# §5 ne vérifie pas ce modèle. Alternative : retirer fallback-model de team-config.yml.
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
> branchée sur l'endpoint `/v1`. On peut l'ajouter à tout moment en ajoutant `ui` à
> `COMPOSE_PROFILES` dans `.env` puis `docker compose up -d` (même mécanisme que le mode
> Ollama) — les autres conteneurs ne sont pas touchés.

> **Cookies par source** : `CONFLUENCE_COOKIE` reçoit les cookies du domaine Confluence,
> `JIRA_COOKIE` ceux du domaine Jira — chaque serveur a son propre `JSESSIONID`, ne pas
> les mélanger. Copier la totalité des cookies du domaine : un de trop ne gêne jamais.

## 5. Préflight — 🐧

```bash
./scripts/check-connections.sh
```

Ce qu'il teste : README, onboarding étape 6. **Tout doit être vert** avant d'indexer —
sinon voir §9. Attention : le modèle `fallback-model` n'est pas vérifié (cf. §4).

**Section « Git local »** (active dès que `sources.git` est présent dans `team-config.yml` ;
la section GitLab API, elle, ne s'exécute que si `sources.gitlab` l'est) :
`GIT_REPOS_DIR` défini et absolu, pas sous `/mnt/` (WARN), pas une racine dangereuse ;
dépôts découverts (même règle que l'API : profondeur 4, bare ou clone) et, pour chacun,
branches cibles résolues (`refs/remotes/<remote>/<b>` ou `refs/heads/<b>`), miroir avec
refspec de fetch (`remote.origin.mirror=true` ou `remote.origin.fetch`), ni shallow ni
clone partiel, `FETCH_HEAD` de moins de 24 h ; si la pile tourne, `docker compose exec -T
rag-api ls /repos` prouve le montage. Un dépôt sans branche cible ou périmé est un
symptôme du §9 (« branche absente », « fetch périmé ») avant d'être un échec d'indexation.

## 6. Indexation initiale — 🐧

```bash
./bootstrap.sh             # 3 à 6 h selon le volume — lancer en fin de journée
```

Suivi pendant l'indexation : dashboard `http://localhost:8080/dashboard.html` (recommandé)
ou compteurs en CLI — les deux sont décrits dans la table du §7.

Interruptible sans risque : tout est en upsert à clés stables, relancer reprend
où c'était. Le smoke test s'exécute automatiquement à la fin.

## 7. Accéder au service

> Les commandes `POST` exigent le header `X-Admin-Token` dès qu'`ADMIN_TOKEN` est
> configuré (les `GET` restent libres). `$ADMIN_TOKEN` vit dans `.env`, pas dans votre
> shell : charger d'abord `set -a; . ./.env; set +a` (sinon header vide → 401).

| Quoi | Où | Comment |
|---|---|---|
| **Chat (UI)** | 🪟 navigateur | `http://localhost:3000` (si profil `ui` actif) — modèle `xrag-<team>` ; localhost est partagé Windows↔WSL, rien à configurer |
| Chat (CLI, streamé) | 🐧 | `curl -N -X POST localhost:8080/api/chat -H 'Content-Type: application/json' -d '{"question":"explique-moi le projet X"}'` |
| Santé | 🪟/🐧 | `http://localhost:8080/actuator/health` → `{"status":"UP"}` |
| **Dashboard d'indexation** | 🪟 navigateur | `http://localhost:8080/dashboard.html` — monitoring temps quasi-réel (chunks/source, tâche en cours, temps écoulé, problèmes) |
| État de l'index | 🐧 | `curl -s localhost:8080/api/admin/status` (brut) ou `.../api/admin/indexing-status` (détaillé) |
| Qualité du graphe | 🐧 | `curl -s localhost:8080/api/admin/graph-quality` (verdict + trous éventuels) |
| Enrichissement LLM du graphe | 🐧 | `curl -X POST -H "X-Admin-Token: $ADMIN_TOKEN" 'localhost:8080/api/admin/enrich?max=150'` (async, bilan dans les logs) — rattache les docs sans nœud ; ajouter `&sources=confluence,jira` pour topic-enrichir des sources déjà rattachées. Auto dans le batch nocturne si `extractors.llm: true` |
| Ré-indexer une source | 🐧 | `curl -X POST -H "X-Admin-Token: $ADMIN_TOKEN" 'localhost:8080/api/admin/sync?source=jira&full=true'` — `full=true` force la ré-indexation (récupère les changements d'extraction, ex. commentaires, même version inchangée) |
| Rafraîchir code + MRs (mode git) | 🐧 | `./scripts/git-fetch-all.sh --sync` — fetch de tous les miroirs de `GIT_REPOS_DIR` puis `POST sync?source=gitlab-code` et `POST sync?source=gitlab-mr` (charge `.env` lui-même : `ADMIN_TOKEN` ; `API_URL` optionnel, défaut `http://localhost:8080`). À la main : les deux `curl -X POST -H "X-Admin-Token: $ADMIN_TOKEN" 'localhost:8080/api/admin/sync?source=…'` après un fetch. Détail et planification : §8 |
| Batch à la demande | 🐧 | `curl -X POST -H "X-Admin-Token: $ADMIN_TOKEN" localhost:8080/api/admin/nightly` |
| Éval du retrieval (recall@k) | 🐧 | `curl -s localhost:8080/api/admin/eval` — rejoue les questions canoniques `eval.cases` de team-config.yml, sans LLM ; rapport aussi dans la notification nocturne |
| Smoke test à la demande | 🐧 | `curl -X POST -H "X-Admin-Token: $ADMIN_TOKEN" localhost:8080/api/admin/smoke-test` (exécuté automatiquement en fin de bootstrap et de batch) |
| Latences vs cibles | 🐧 | `./scripts/measure-latency.sh` |

## 8. Exploitation courante

- **Batch nocturne à 02:00** : automatique **si le poste et WSL tournent à cette heure**.
  Poste éteint la nuit → lancer le rattrapage le matin : `curl -X POST -H "X-Admin-Token: $ADMIN_TOKEN" localhost:8080/api/admin/nightly`.
- **Temps réel GitLab (webhooks)** : sans eux, code et MRs ne sont à jour qu'à J-1 (batch).
  Dans GitLab (groupe → Settings → Webhooks) : URL `http://<hôte>:8080/api/webhooks/gitlab`,
  événements **push** + **merge request**, *Secret token* = valeur de `GITLAB_WEBHOOK_TOKEN`
  du `.env` (vérifiée via le header `X-Gitlab-Token` ; **vide = webhook accepté sans
  authentification**). Critères de bon fonctionnement : VALIDATION.md §6.
- **Fraîcheur en mode git local** (`sources.git`) : le webhook n'a pas de sens (GitLab
  n'est pas joignable, et l'application ne fetch jamais) — c'est **vous** qui rafraîchissez
  les miroirs. **Une seule commande à planifier** : `scripts/git-fetch-all.sh --sync`
  (verrou `flock` sur `<dossier>/.fetch.lock` — un passage à la fois ; pour chaque dépôt sous
  `GIT_REPOS_DIR`, profondeur 4 comme l'API :
  `git remote update --prune` (bare) ou `git fetch --all --prune` (clone) sous `timeout 300`,
  puis `gc --auto` non détaché ; horodatage `.last-fetch` et journal `fetch.log` dans le
  dossier ; code retour ≠ 0 si un dépôt échoue ; `--sync` charge `.env` puis enchaîne
  `POST /api/admin/sync?source=gitlab-code` et `?source=gitlab-mr` sur
  `${API_URL:-http://localhost:8080}` avec `X-Admin-Token`). Les deux syncs sont
  asynchrones côté API ; le résultat se lit sur le dashboard (`gitlab-code`, `gitlab-mr`).
  Le connecteur ne ré-embedde que les fichiers dont le blob a changé (récolte complète,
  version = id de blob) et re-balaie `rescan-days` (30 j) derrière le curseur des MRs : un
  fetch tardif ne crée pas de trou. Un miroir sans fetch depuis plus de 26 h déclenche un
  WARN « miroir périmé » dans les logs à chaque sync.
  Planification (01:15 avant le batch de 02:00, puis toutes les 2 h en journée) :
  - 🪟 **Windows (Docker dans WSL)** — tâche planifiée qui lance le script dans la distro :
    ```powershell
    # Une fois, PowerShell. Rattrapage à l'ouverture de session (schtasks n'accepte pas /RI avec ONLOGON) :
    schtasks /Create /TN "xrag-git-fetch-logon" /SC ONLOGON /TR "wsl.exe -d Ubuntu -u <vous> -- bash -lc 'cd ~/x-rag-project-assistant && ./scripts/git-fetch-all.sh --sync'"
    # Toutes les 2 h en journée :
    schtasks /Create /TN "xrag-git-fetch" /SC HOURLY /MO 2 /ST 09:00 /TR "wsl.exe -d Ubuntu -u <vous> -- bash -lc 'cd ~/x-rag-project-assistant && ./scripts/git-fetch-all.sh --sync'"
    # + le passage nocturne avant le batch de 02:00 (poste allumé)
    schtasks /Create /TN "xrag-git-fetch-nuit" /SC DAILY /ST 01:15 /TR "wsl.exe -d Ubuntu -u <vous> -- bash -lc 'cd ~/x-rag-project-assistant && ./scripts/git-fetch-all.sh --sync'"
    ```
    (`cd` dans le dépôt : `--sync` lit `./.env`.) `~/xrag-repos/fetch.log` garde la trace
    de chaque passage.
  - 🐧 **Linux natif** — `crontab -e` (5 champs) :
    ```cron
    15 1 * * *        cd ~/x-rag-project-assistant && ./scripts/git-fetch-all.sh --sync
    0 9-18/2 * * 1-5  cd ~/x-rag-project-assistant && ./scripts/git-fetch-all.sh --sync
    ```
  - **Rattrapage du matin** (poste éteint la nuit) : `./scripts/git-fetch-all.sh --sync`,
    puis éventuellement le batch complet (`POST /api/admin/nightly`, ci-dessus) pour les
    fiches projet et le graphe.
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
| Mode git : sync `gitlab-code` en `ERREUR sources.git.root introuvable dans le conteneur` | Volume non monté : `GIT_REPOS_DIR` vide, relatif, ou chemin inexistant côté WSL | `GIT_REPOS_DIR=/home/<vous>/xrag-repos` (absolu) dans `.env`, puis `docker compose up -d rag-api` ; vérifier `docker compose exec -T rag-api ls /repos` |
| Mode git : sync en `ERREUR GIT_REPOS_DIR = … exposerait bien plus que les dépôts de l'équipe` (ou `sources.git.root = …`) | `GIT_REPOS_DIR` (chemin hôte, lu du `.env` par le conteneur) désigne une racine trop large : `/`, `/home`, `/home/<vous>`, `/root`, `/mnt`, `/mnt/c`, `/mnt/c/Users[/<vous>]`, `/tmp`, `/opt`… — ou plus de 200 dépôts découverts | Monter un dossier **dédié** aux miroirs (§3), ex. `/home/<vous>/xrag-repos` ; au-delà de 200 dépôts, lister le périmètre dans `sources.git.repos` |
| Mode git : `Aucun dépôt git sous /repos` | Dossier vide, miroirs à plus de 4 niveaux sous la racine, symlinks (ignorés) | `./scripts/git-mirror-init.sh repos.list ~/xrag-repos` (miroirs à ≤ 4 niveaux : `root/projet.git` … `root/grp/sous-grp/sous-sous-grp/projet.git`, sinon les lister dans `sources.git.repos`) ; pas de liens symboliques |
| Mode git : sync en `ERREUR sources.git.repos : « … » absent ou hors de la racine` / `n'est pas un dépôt git` | Une entrée explicite de `sources.git.repos` ne correspond à aucun dépôt (fail-closed : jamais de dépôt ignoré en silence) | Corriger la liste ou créer le miroir manquant |
| Mode git : sync en `ERREUR Dépôt X : aucune des branches [main, develop] n'existe (ni refs/remotes/…, ni refs/heads/…)` | Branche cible absente du dépôt (nommée autrement : `master`, `trunk`), ou clone jamais fetché — fail-closed : ses MRs ouvertes ne peuvent pas être inventoriées | Aligner `sources.git.branches` sur les branches réelles, `git fetch` du clone, ou retirer le dépôt via `sources.git.repos` ; le préflight §5 le signale dépôt par dépôt |
| Mode git : sync en `ERREUR Deux dépôts résolvent le même projet « X »` | Un fork ou un doublon (même dernier segment d'URL de remote dans deux groupes) : chunks et MRs s'écraseraient mutuellement | Retirer le doublon de `GIT_REPOS_DIR` ou restreindre `sources.git.repos` |
| Mode git : `clone PARTIEL (--filter) non supporté` | Miroir créé avec `--filter=blob:none` (promisor) : JGit ne peut pas récupérer les objets manquants | Recloner en miroir **complet** (`git clone --mirror <url>`) |
| Mode git : WARN `clone SHALLOW (--depth) — historique tronqué` | Miroir créé avec `--depth` : MRs anciennes irrécupérables | `git -C ~/xrag-repos/<grp>/<proj>.git fetch --unshallow` |
| Mode git : préflight « miroir sans refspec de fetch » / `git remote update` ne ramène rien | Dépôt bare créé sans `--mirror` (pas de `remote.origin.fetch`) : le fetch ne met aucune ref à jour | `git -C <dépôt>.git config remote.origin.fetch '+refs/*:refs/*'` et `remote.origin.mirror true`, ou recloner avec `--mirror` |
| Mode git : WARN `dernier fetch il y a N h — miroir périmé ?` ; réponses en retard sur GitLab | Plus de 26 h sans fetch (mtime de `FETCH_HEAD`) : tâche planifiée absente/échouée, credentials GCM expirés | `./scripts/git-fetch-all.sh --sync` à la main (voir `fetch.log`), vérifier la planification §8, `git ls-remote --heads <url>` pour tester les credentials |
| Mode git : sync en `ERREUR … objet manquant … git gc/fetch concurrent ?` | Un `git gc` (ou fetch) a réécrit les packs pendant la lecture ; l'API a réouvert le dépôt une fois puis a abandonné (fail-closed : rien n'est supprimé) | Relancer la sync une fois le fetch terminé ; `git-fetch-all.sh` pose `gc.autoDetach=false` justement pour que le gc finisse avant le `--sync` |
| Mode git : WARN `fichier .git (worktree lié ou sous-module) dont la cible gitdir n'est pas visible` — dépôt ignoré | Un `git worktree add` / sous-module : le `.git` est un fichier `gitdir: <chemin hôte>` qui n'existe pas dans le conteneur | Monter le dépôt principal, ou (recommandé) un miroir bare (§3) |
| Mode git : une MR fusionnée sur GitLab reste « ouverte », ou une branche poussée sans MR apparaît comme MR | Heuristique « branche distante non fusionnée » : squash/rebase sans commit de fusion et branche non supprimée, ou branche de travail poussée | Supprimer la branche distante (elle disparaît à la sync suivante, `--prune`) ; ajouter un motif à `open-branches-exclude` ; côté GitLab, stratégie « merge commit » ou squash template avec `%{reference}` (§3) |
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

### Basculer API GitLab ↔ dépôts git locaux

Les deux transports partagent l'identifiant de source `gitlab-code`, les clés de chunk
(`gitlab-code:<slug>@<branche>/<fichier>`) et la table `merge_requests` : la bascule ne
duplique **aucun chunk** et conserve graphe et extracteurs. Marche à suivre :

1. `team-config.yml` : remplacer `sources.gitlab` par `sources.git` (ou l'inverse — jamais
   les deux) ; `.env` : `GIT_REPOS_DIR` (mode git) ou `GITLAB_TOKEN` (mode API) ;
   `docker compose up -d rag-api`.
2. Sync du code : la première sync **ré-embedde tous les fichiers une fois** — la version
   d'un fichier passe du SHA de branche (API) à l'id de blob (git), ou inversement — puis
   redevient incrémentale. Prévoir l'équivalent d'un bootstrap pour le code
   (`POST /api/admin/sync?source=gitlab-code`, suivi sur le dashboard).
3. Sync des MRs : les MRs de l'ancien transport **restent en table** avec leurs ids
   (`gitlab:<pid>:<iid>` côté API ; `git:<slug>:…` / `git-branch:<slug>:…` côté git) —
   doublons possibles dans `listMergeRequests` / `countMergeRequests`. Purge manuelle,
   après la première sync réussie du nouveau transport :

```sql
-- API -> git : retirer les MRs issues de l'API (table + nœuds/arêtes du graphe)
DELETE FROM graph_edges WHERE src LIKE 'mr:gitlab:%' OR dst LIKE 'mr:gitlab:%';
DELETE FROM graph_nodes WHERE id LIKE 'mr:gitlab:%';
DELETE FROM merge_requests WHERE id LIKE 'gitlab:%';

-- git -> API : retirer les MRs reconstituées depuis git
DELETE FROM graph_edges WHERE src LIKE 'mr:git:%' OR dst LIKE 'mr:git:%'
                           OR src LIKE 'mr:git-branch:%' OR dst LIKE 'mr:git-branch:%';
DELETE FROM graph_nodes WHERE id LIKE 'mr:git:%' OR id LIKE 'mr:git-branch:%';
DELETE FROM merge_requests WHERE id LIKE 'git:%' OR id LIKE 'git-branch:%';
```

(`docker compose exec postgres psql -U xrag -d xrag`. Attention aux motifs : `mr:git%`
sans le `:` engloberait aussi `mr:gitlab:`.) Les fichiers supprimés du dépôt ne sont
purgés par aucun des deux transports — comportement inchangé.
