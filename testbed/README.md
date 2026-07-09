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
| `confluence/` | Pages à créer dans un space factice (ex. `SAND`) |
| `jira/` | Issues à créer dans un projet factice (ex. `SAND`) |
| `increments/` | Fichiers des étapes incrémentales (voir `scenario.md`) |
| `scenario.md` | **Vérité terrain** : graphe attendu, pièges, questions canoniques, déroulé |
| `team-config.testbed.yml` | Config d'instance pointant sur le groupe sandbox |
| `setup-gitlab.sh` | Création du groupe/projets/MRs GitLab + étapes incrémentales |

Chaque projet contient aussi des **pièges volontaires** (topic Kafka dynamique,
appel `WebClient` hors Feign, MR sans clé Jira, alias mal orthographié) : le
scénario documente ce que l'extraction déterministe **ne doit pas** produire —
c'est la mesure factuelle des limites, celle qui alimente la décision
« extraction LLM / extracteur AST » (décision d'architecture n°10).

## Démarrage rapide

```bash
# 1. Créer le sandbox GitLab (sous-groupe + 3 projets + 3 MRs)
export GITLAB_BASE_URL=https://gitlab.example.com
export GITLAB_TOKEN=glpat-...          # scope api
export GITLAB_PARENT_GROUP=passerelle  # le sous-groupe xrag-sandbox sera créé dessous
./testbed/setup-gitlab.sh init

# 2. (Optionnel) Créer le space Confluence SAND et le projet Jira SAND
#    en collant le contenu de confluence/ et jira/ (manuel, 10 min)

# 3. Pointer une instance x-rag sur le sandbox
cp testbed/team-config.testbed.yml team-config.yml   # adapter les base-url
docker compose up -d && ./bootstrap.sh               # indexation : quelques minutes

# 4. Dérouler les assertions de scenario.md (SQL + questions canoniques)
# 5. Étapes incrémentales
./testbed/setup-gitlab.sh increment   # nouvelle relation → visible après webhook/nightly
./testbed/setup-gitlab.sh prune       # suppression de fichier → purge des orphelins
```

Les questions canoniques de `scenario.md` peuvent être reprises dans la config
du smoke test nocturne : il devient alors un test de **justesse**, plus seulement
de disponibilité.
