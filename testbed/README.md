# Testbed — corpus factice de validation du RAG

Trois mini-projets reliés entre eux, avec MRs, pages Confluence et issues Jira
factices, formant un **graphe attendu connu d'avance** (vérité terrain). Objectif :
vérifier la justesse du RAG (extraction de relations, retrieval, citations, tools,
sync incrémentale) **sans toucher aux vrais projets**.

Complémentaire de `VALIDATION.md` : le testbed valide la **logique** (les réponses
sont-elles justes ?), la validation terrain valide la **tenue en charge** (latences
sur volumétrie réelle).

## Contenu

| Élément | Rôle |
|---|---|
| `projects/fake-orders` | Java — publie Kafka `orders`, entité `@Table("orders")` |
| `projects/fake-billing` | Java — consomme `orders`, `@FeignClient` vers fake-orders, lit la table `orders` |
| `projects/fake-front` | TypeScript — `HttpClient` vers `environment.billingUrl` |
| `confluence/` | 5 pages sous la page parente « XRAG-SANDBOX » d'un space existant |
| `jira/` | 4 issues du projet factice `XRAGSAND` (liées aux MRs et aux pages) |
| `increments/` | Fichiers des étapes incrémentales (voir `scenario.md`) |
| `scenario.md` | **Vérité terrain** : graphe attendu, pièges, questions canoniques, déroulé |
| `team-config.testbed.yml` | Config d'instance pointant sur le groupe sandbox |
| `setup-gitlab.sh` | Groupe + 3 projets + 3 MRs GitLab, étapes `increment`/`prune` |
| `setup-git-local.sh` | **Variante sans GitLab** : mêmes projets et MRs en dépôts git **locaux** (miroirs bare lus par `sources.git`), étapes `increment`/`prune`/`merge-mr1` |
| `setup-confluence.sh` | Page parente « XRAG-SANDBOX » + les 5 pages (requiert pandoc) |
| `setup-jira.sh` | Création des issues `XRAGSAND-1..4` + liens entre issues |

Chaque source contient des **pièges volontaires** (topic Kafka dynamique, appel
`WebClient` hors Feign, MR sans clé Jira, alias mal orthographié « Fake Ordres ») :
le scénario documente ce que l'extraction déterministe **ne doit pas** produire —
c'est la mesure factuelle de ses limites, celle qui alimente la décision
« extraction LLM / extracteur AST » (décision d'architecture n°10).

## Nommage du bac à sable

Tout le sandbox s'identifie d'un coup d'œil sous le nom **XRAG-SANDBOX**
(x pour expérimental) :

| Où | Nom d'affichage | Clé / chemin technique |
|---|---|---|
| GitLab | XRAG-SANDBOX | sous-groupe `x-rag-sandbox` |
| Dépôts git locaux (variante sans GitLab) | — | dossier `<dossier>/x-rag-sandbox/<projet>.git` (miroirs bare) |
| Confluence | XRAG-SANDBOX | **page parente** « XRAG-SANDBOX » dans un space existant où vous pouvez écrire |
| Jira | XRAG-SANDBOX | projet **`XRAGSAND`** (issues `XRAGSAND-1..4`) |

Pourquoi la clé Jira n'est pas littéralement `XRAG-SANDBOX` : les clés de projet
Jira **n'acceptent pas les tirets** et sont limitées à 10 caractères par défaut —
`XRAGSAND` respecte les deux. À la création du projet, mettez « XRAG-SANDBOX »
comme **nom** et `XRAGSAND` comme **clé**.

Côté Confluence, **aucun space dédié n'est requis** (leur création est souvent
réservée aux admins) : les pages vivent sous une page parente « XRAG-SANDBOX »
dans un space où vous avez déjà les droits. ⚠️ L'indexation Confluence du RAG se
fait **par space entier** (`sources.confluence.spaces`) : préférez un space
isolé — typiquement votre **space personnel** (clé `~login`, créable sans
admin) — sinon le contenu réel du space partagé polluera la vérité terrain du
scénario (verdict graph-quality, réponses aux questions canoniques).

## Tokens et permissions

Deux familles de tokens, à ne pas confondre :

| Usage | Où | Droits nécessaires |
|---|---|---|
| **Provisioning** (scripts `setup-*.sh`) | variables d'env du shell | écriture — création de groupes/projets/MRs/pages/issues |
| **Indexation** (instance x-rag) | `.env` (`GITLAB_TOKEN`, `CONFLUENCE_TOKEN`, `JIRA_TOKEN`) | **lecture seule** suffit |

Bonnes pratiques (mêmes règles que le PAT GitHub du CLAUDE.md) : expiration
courte, révocation après usage pour les tokens d'écriture ; l'instance, elle,
garde des tokens de lecture.

### GitLab (`setup-gitlab.sh`)

- **PAT** avec le scope **`api`** (création de sous-groupe, projets, commits,
  branches, MRs, merge de MR-3).
- Le compte doit être **Owner ou Maintainer du groupe parent**
  (`GITLAB_PARENT_GROUP`) : la création de sous-groupes l'exige.
- Côté instance (`.env`) : un PAT en scopes **`read_api` + `read_repository`**
  suffit pour indexer ; le webhook n'a pas besoin de token GitLab (il utilise le
  secret `GITLAB_WEBHOOK_TOKEN` entrant).

### Sans GitLab : dépôts git locaux (`setup-git-local.sh`)

Pour valider le mode `sources.git` (politique cyber interdisant le token GitLab —
RUNBOOK §3) : **aucun token, aucun réseau**, seulement `git` sur le poste. Le script
reconstruit le même sandbox en **miroirs bare** `<dossier>/x-rag-sandbox/fake-orders.git`,
`fake-billing.git`, `fake-front.git`, avec l'historique que le connecteur sait
reconstituer en MRs :

| MR du scénario | Forme git | Ce que le connecteur en fait |
|---|---|---|
| MR-1 « XRAGSAND-1 Suivi de commande » (fake-orders) | branche `feat/xragsand-1-suivi-commande` **non fusionnée**, 1 commit daté du 06/01 | MR **ouverte présumée** `git-branch:fake-orders:feat/xragsand-1-suivi-commande` — la plus vieille |
| MR-2 « Refonte du calcul de TVA » (fake-billing) | branche `feat/refonte-tva` non fusionnée, 1 commit du 07/01, sans clé Jira | MR ouverte présumée `git-branch:fake-billing:feat/refonte-tva` |
| MR-3 « XRAGSAND-3 Export CSV des factures » (fake-billing) | commit de fusion `--no-ff` du 08/01 au **format GitLab exact** (« Merge branch '…' into 'main' » / titre / « See merge request passerelle/x-rag-sandbox/fake-billing!3 ») | MR **merged** `git:fake-billing:3`, **iid 3** |

Dates de commit fixes et croissantes (`GIT_AUTHOR_DATE`/`GIT_COMMITTER_DATE`) : l'ancienneté
des MRs est reproductible. Commits signés désactivés et identité imposée
(`git -c commit.gpgsign=false -c user.name=testbed -c user.email=testbed@example.com`) :
l'auteur reconstitué est `testbed` quelle que soit la config du poste. Chaque miroir porte
un remote `origin` **factice** (`https://gitlab.example.com/passerelle/x-rag-sandbox/<projet>.git`,
modifiable via `SANDBOX_REMOTE_BASE`) : c'est de cette URL que l'API déduit le slug
(`fake-orders` → alias `fakeorders`), le chemin complet et les liens `…/-/blob/…`,
`…/-/merge_requests/3` — elle n'est jamais contactée. Conséquences :

- **ne pas lancer `scripts/git-fetch-all.sh`** sur ce dossier (le remote n'existe pas) :
  les étapes `increment`/`prune`/`merge-mr1` poussent directement dans les miroirs ;
- le préflight `check-connections.sh` émet un WARN « jamais fetché (pas de FETCH_HEAD) »
  sur chaque miroir : **attendu**, l'API ne s'en sert que pour l'alerte « miroir périmé » ;
- pas de webhook : après chaque étape, `POST /api/admin/sync?source=gitlab-code` puis
  `?source=gitlab-mr` (header `X-Admin-Token`), ou le nightly.

`<dossier>` est la valeur à mettre dans `GIT_REPOS_DIR` (`.env`) : chemin **absolu**,
dossier **dédié** (le script refuse `/tmp`, `/home`… comme l'API), dans le FS WSL. Côté
instance : `team-config.testbed.yml` contient le bloc `sources.git` en commentaire, à
substituer au bloc `gitlab:` (les deux sont exclusifs).

### Confluence (`setup-confluence.sh`)

- **Data Center/Server** : PAT (profil → *Personal Access Tokens*), envoyé en
  `Bearer`. Les PAT DC n'ont pas de scopes : ils héritent des droits du compte —
  il suffit d'avoir la permission **« Ajouter des pages »** sur le space visé
  (`CONFLUENCE_SPACE`). Aucun droit admin : pas de space à créer, le script pose
  une page parente « XRAG-SANDBOX » et les 5 pages dessous.
- **Cloud** : API token + email → définir `CONFLUENCE_USER`, le script bascule
  en Basic auth.
- Côté instance : un compte avec **lecture** du space suffit.

### Jira (`setup-jira.sh`)

- **Data Center/Server** : PAT en `Bearer`. Le compte doit avoir, sur le projet
  `XRAGSAND` : **Create Issues** et **Link Issues** (et *Transition Issues* pour
  passer XRAGSAND-1/2 « En cours » et XRAGSAND-3 « Terminée » — étape manuelle, les
  workflows varient selon l'instance).
- **Cloud** : API token + email → définir `JIRA_USER` (Basic auth).
- Le projet `XRAGSAND` doit exister et être **vierge** (la numérotation doit
  commencer à XRAGSAND-1 : les MRs et pages référencent ces clés littéralement).
- Côté instance : **Browse Projects** en lecture suffit.

## Démarrage rapide

```bash
# 1. GitLab : sous-groupe + 3 projets + 3 MRs
export GITLAB_BASE_URL=https://gitlab.example.com
export GITLAB_TOKEN=glpat-...          # scope api, Owner/Maintainer du parent
export GITLAB_PARENT_GROUP=passerelle  # le sous-groupe x-rag-sandbox sera créé dessous
./testbed/setup-gitlab.sh init

# 1 bis. SANS GitLab (mode sources.git) : mêmes projets et MRs en miroirs bare locaux
./testbed/setup-git-local.sh ~/xrag-testbed init   # puis .env : GIT_REPOS_DIR=/home/<vous>/xrag-testbed
#   -> team-config.yml : bloc sources.git (commenté dans team-config.testbed.yml) à la place de gitlab:

# 2. Confluence : page parente « XRAG-SANDBOX » + 5 pages dans un space existant
export CONFLUENCE_BASE_URL=https://confluence.example.com
export CONFLUENCE_TOKEN=...            # PAT DC (ou API token + CONFLUENCE_USER en Cloud)
export CONFLUENCE_SPACE='~votre-login' # space isolé recommandé (l'indexation est par space)
./testbed/setup-confluence.sh

# 3. Jira : issues XRAGSAND-1..4 — projet « XRAG-SANDBOX », clé XRAGSAND, vierge
export JIRA_BASE_URL=https://jira.example.com
export JIRA_TOKEN=...                  # PAT DC (ou API token + JIRA_USER en Cloud)
./testbed/setup-jira.sh

# 4. Pointer une instance x-rag sur le sandbox
cp testbed/team-config.testbed.yml team-config.yml   # adapter les base-url
docker compose up -d && ./bootstrap.sh               # indexation : quelques minutes

# 5. Dérouler les assertions de scenario.md (SQL + questions canoniques)
# 6. Étapes incrémentales, à votre rythme
./testbed/setup-gitlab.sh increment   # nouvelle relation → webhook/nightly
./testbed/setup-gitlab.sh prune       # suppression → purge des orphelins
#    Variante git local (pas de webhook : POST /api/admin/sync?source=gitlab-code|gitlab-mr après chaque étape)
./testbed/setup-git-local.sh ~/xrag-testbed increment
./testbed/setup-git-local.sh ~/xrag-testbed prune
./testbed/setup-git-local.sh ~/xrag-testbed merge-mr1   # étape D : MR-1 fusionnée, branche supprimée
```

Les questions canoniques de `scenario.md` peuvent être reprises dans la config
du smoke test nocturne : il devient alors un test de **justesse**, plus seulement
de disponibilité.
