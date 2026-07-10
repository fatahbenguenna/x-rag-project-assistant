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

> **Ollama : rien à installer**, ni côté Windows ni côté WSL — le compose l'embarque en
> conteneur. Si l'application Ollama Windows est installée, **quittez-la** (icône barre des
> tâches) pendant l'utilisation de la pile : port 11434 et RAM en double sinon.

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
> rafraîchissement). Cible pérenne : compte de service → mode basic (`*_USER` + `*_TOKEN`),
> sans autre changement. Ne jamais coller ces valeurs ailleurs que dans le `.env`.

## 4. Démarrer la pile — 🐧

```bash
# Premier lancement : --build construit l'image rag-api localement (5-15 min, puis caché).
# L'avertissement « pull access denied for xrag/rag-api » est NORMAL en mode dev :
# cette image se construit chez vous (build: .), elle n'existe sur aucun registry.
docker compose up -d --build               # pile de base : postgres, ollama, rag-api
# OU, pour inclure l'interface de chat Open WebUI (recommandé pour les humains) :
docker compose --profile ui up -d --build  # le drapeau ui se place avant « up »

docker exec xrag-ollama ollama pull qwen2.5:7b-instruct
docker exec xrag-ollama ollama pull qwen2.5:3b
docker exec xrag-ollama ollama pull bge-m3
docker compose ps                          # tout doit être Up (postgres healthy)
```

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

Suivi pendant l'indexation : `curl -s localhost:8080/api/admin/status` (compteurs en
croissance). Interruptible sans risque : tout est en upsert à clés stables, relancer reprend
où c'était. Le smoke test s'exécute automatiquement à la fin.

## 7. Accéder au service

| Quoi | Où | Comment |
|---|---|---|
| **Chat (UI)** | 🪟 navigateur | `http://localhost:3000` (si `--profile ui`) — modèle `xrag-<team>` ; localhost est partagé Windows↔WSL, rien à configurer |
| Chat (CLI, streamé) | 🐧 | `curl -N -X POST localhost:8080/api/chat -H 'Content-Type: application/json' -d '{"question":"explique-moi le projet X"}'` |
| Santé | 🪟/🐧 | `http://localhost:8080/actuator/health` → `{"status":"UP"}` |
| État de l'index | 🐧 | `curl -s localhost:8080/api/admin/status` |
| Qualité du graphe | 🐧 | `curl -s localhost:8080/api/admin/graph-quality` (verdict + trous éventuels) |
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
| Préflight `[KO] ... HTTP 401` | Cookie expiré ou token invalide | §8 rafraîchissement des cookies |
| Préflight `[KO] ... HTTP 404` | Context path absent du `base-url`, ou clé espace/projet erronée | Corriger `team-config.yml` (ex. `https://host/confluence`) |
| Préflight `200 mais contenu inattendu (HTML)` | Redirection vers la mire SSO | Cookies incomplets — recopier **tous** les cookies du domaine |
| Ollama injoignable | Conteneur arrêté, ou confusion avec l'Ollama Windows | `docker compose up -d` ; quitter l'app Ollama Windows |
| Conteneur `ollama` tué / OOM | Limite mémoire WSL2 | §1.2 `.wslconfig` `memory=20GB`, `wsl --shutdown` |
| Indexation très lente, I/O saturées | Dépôt cloné sous `/mnt/c` | Recloner dans `~` (FS ext4 de WSL) |
| Compteurs `status` figés à 0 | Connexion source en échec en cours de route | `docker compose logs rag-api`, re-préflight |
| 1er token > 30 s après une pause | Modèle déchargé de la RAM | `OLLAMA_KEEP_ALIVE=24h` (défaut du compose) + warm-up 07:30 du batch |
| Pas de batch cette nuit | Poste/WSL éteint à 02:00 | Rattrapage manuel (§8) |
| `curl : option -u inconnue` | Vous êtes dans PowerShell (alias `Invoke-WebRequest`) | Utiliser le terminal 🐧 WSL, ou `curl.exe` |

## 10. Mise à jour de l'instance — 🐧

```bash
# En développement (build local) :
git pull && docker compose build rag-api && docker compose up -d

# En mode image versionnée (RAG_API_IMAGE dans .env) :
docker compose pull && docker compose up -d    # Liquibase migre au démarrage
```

Jamais de perte d'index lors d'une mise à jour : le schéma évolue par migrations, les
données restent dans les volumes.
