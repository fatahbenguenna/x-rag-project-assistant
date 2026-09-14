# Scénario de validation — vérité terrain

Ce document est le contrat du testbed : ce que l'index **doit** contenir, ce qu'il
**ne doit pas** contenir (pièges), et les questions dont la réponse est connue.

Il vaut pour les deux transports du code et des MRs : **API GitLab** (`setup-gitlab.sh`,
`sources.gitlab`) et **dépôts git locaux** (`setup-git-local.sh`, `sources.git` — README
du testbed). Le code indexé, les extracteurs et les questions sont identiques ; les MRs,
elles, sont **reconstituées depuis l'historique git** dans le second cas. Les différences
sont marquées 🔀 **git local** dans chaque section.

## 1. Graphe attendu après indexation initiale

| Arête | Provenance | Vérifie |
|---|---|---|
| `project:fakeorders -PUBLISHES-> topic:orders` | `OrderEventPublisher.java` (`kafkaTemplate.send("orders", ...)`) | extraction Kafka producteur |
| `project:fakebilling -CONSUMES-> topic:orders` | `OrdersListener.java` (`@KafkaListener(topics = "orders")`) | extraction Kafka consommateur |
| `project:fakebilling -CALLS_API-> project:fakeorders` | `OrdersClient.java` (`@FeignClient(name = "fake-orders")`) | Feign + résolution d'alias |
| `project:fakeorders -SHARES_TABLE- project:fakebilling` | `Order.java` et `OrderReadModel.java` (`@Table(name = "orders")`) | table partagée |
| `project:fakefront -CALLS_API-> project:fakebilling` | `billing.service.ts` (`HttpClient` + `environment.billingUrl`) | extracteur TypeScript |
| `mr:... -MODIFIES-> ...` pour chaque MR | fichiers touchés des 3 MRs | mapping MR → graphe |
| `mr:MR-1 -REFERENCES-> issue:XRAGSAND-1` | clé Jira dans le titre de la MR-1 | regex clés Jira |
| `page:* -DOCUMENTS-> project:*` | les 5 pages sous la page parente « XRAG-SANDBOX » mentionnent les projets (fiches 02/03, architecture 01, runbook 04, post-mortem 05) | extraction Confluence + alias |
| `page:* -REFERENCES-> issue:XRAGSAND-*` | XRAGSAND-1 (pages 01, 02), XRAGSAND-2 (pages 03, 04, 05), XRAGSAND-3 (page 03) | regex clés Jira dans les pages |
| `page:01 -LINKS_TO-> page:02/03` | liens Confluence posés à la création (voir note des pages) | liens entre pages |
| `issue:XRAGSAND-* -> project:...` + `XRAGSAND-1 -LINKS_TO- XRAGSAND-2/XRAGSAND-4` | issues et liens créés par `setup-jira.sh` | extraction Jira |

Vérification SQL directe (les tables sont celles des changelogs Liquibase) :

```sql
-- Les arêtes structurantes entre projets
SELECT src, type, dst FROM graph_edges
WHERE type IN ('PUBLISHES','CONSUMES','CALLS_API','SHARES_TABLE','DEPENDS_ON')
ORDER BY src, type;

-- La résolution d'alias a bien produit des nœuds canoniques uniques
SELECT id, type, name FROM graph_nodes WHERE type = 'PROJECT' ORDER BY id;

-- Le pont RAG <-> graphe : des chunks rattachés aux nœuds du sandbox
SELECT count(*) FROM rag_chunks WHERE 'project:fakeorders' = ANY(node_ids);
```

Et via l'éval : `curl -s localhost:8080/api/admin/graph-quality` — verdict attendu
**sans trou majeur** sur ce corpus (sinon, l'extraction a un bug, pas le corpus).

🔀 **git local** — mêmes arêtes, mais les nœuds MR portent les ids reconstitués
(`mr:<id de la table merge_requests>`) : `mr:git-branch:fake-orders:feat/xragsand-1-suivi-commande`
(MR-1), `mr:git-branch:fake-billing:feat/refonte-tva` (MR-2), `mr:git:fake-billing:3` (MR-3).
`REFERENCES` vers `issue:XRAGSAND-1` et `issue:XRAGSAND-3` : la clé est reprise du titre, de
la branche et de la ligne « Clés Jira : … » de la description reconstituée. La table des MRs
est la vérité terrain la plus directe :

```sql
-- 🔀 git local : 3 MRs reconstituées — MR-3 merged avec iid 3, MR-1/MR-2 opened avec iid 0
-- (iid inconnu : citées par leur id dans les tools), MR-1 la plus ancienne (created_at =
-- 1er commit de la branche), auteur = partie locale de l'email des commits
SELECT id, project, iid, state, title, author, created_at::date, merged_at::date
FROM merge_requests ORDER BY created_at;
-- attendu :
-- git-branch:fake-orders:feat/xragsand-1-suivi-commande | fake-orders  | 0 | opened | XRAGSAND-1 Suivi de commande       | testbed | 2026-01-06 |
-- git-branch:fake-billing:feat/refonte-tva               | fake-billing | 0 | opened | Refonte du calcul de TVA           | testbed | 2026-01-07 |
-- git:fake-billing:3                                     | fake-billing | 3 | merged | XRAGSAND-3 Export CSV des factures | testbed | 2026-01-08 | 2026-01-08
```

(`project` = slug déduit de l'URL du remote factice, résolu ensuite par la table d'alias
vers `project:fakeorders` / `project:fakebilling`.) Aucune MR pour les commits directs
« chore: état initial du testbed » : sans clé Jira, un commit direct n'est pas une MR
synthétique.

## 2. Pièges — ce que l'index NE doit PAS contenir

| Piège | Fichier | Comportement attendu |
|---|---|---|
| Topic Kafka dynamique (`"audit-" + region`) | `AuditTrailRouter.java` | **aucune** arête `PUBLISHES` vers `topic:audit-*` — limite connue de l'extraction déterministe |
| Appel HTTP via `WebClient` (pas Feign) | `LegacyOrdersWebClient.java` | **aucune** arête `CALLS_API` supplémentaire depuis fake-billing |
| MR sans clé Jira | MR-2 « Refonte du calcul de TVA » | arêtes `MODIFIES` présentes, **aucune** `REFERENCES` |
| Alias mal orthographié « Fake Ordres » | `confluence/04-runbook-facturation.md` | mention **non résolue** tant que l'alias n'est pas déclaré ; ajoutez ensuite `"Fake Ordres"` aux alias de `fakeorders`, re-synchronisez, et vérifiez que l'arête `DOCUMENTS` apparaît — c'est le test de la table d'alias |
| 🔀 git local — branche source de MR-3 conservée après fusion | `feat/xragsand-3-export-csv` dans `fake-billing.git` | **aucune** MR ouverte `git-branch:fake-billing:feat/xragsand-3-export-csv` : une branche dont la tête est contenue dans `main` n'est pas « non fusionnée » |
| 🔀 git local — commits directs sans clé Jira | « chore: état initial du testbed », étapes `increment` et `prune` | **aucune** MR synthétique `git:<slug>:commits:<sha>` ; `countMergeRequests(all)` reste à 3 |

Si un de ces pièges produit quand même une arête : faux positif d'extraction (bug).
Si vous voulez que ces cas soient couverts un jour : c'est le périmètre du chantier
« extraction LLM nocturne » ou d'un extracteur AST type Graphify — vous avez
maintenant la mesure factuelle.

## 3. Questions canoniques (réponses connues)

| # | Question | Réponse attendue | Sources attendues | Voie |
|---|---|---|---|---|
| Q1 | « Comment communiquent fake-orders et fake-billing ? » | 3 canaux : topic Kafka `orders`, appel API Feign billing→orders, table `orders` partagée | `OrderEventPublisher.java`, `OrdersListener.java`, `OrdersClient.java` | graphe + RAG |
| Q2 | « Quelle MR ouverte est la plus vieille ? » | MR-1 « XRAGSAND-1 Suivi de commande » (fake-orders) | métadonnées MR | tools SQL |
| Q3 | « Combien de MRs sont ouvertes ? » | 2 (MR-1 et MR-2) | métadonnées MR | tools SQL |
| Q4 | « Qui publie sur le topic orders ? » | fake-orders | `OrderEventPublisher.java` | graphe |
| Q5 | « Explique-moi le projet fake-billing en 3 principes » | consomme les commandes, facture, expose les factures au front | fiche projet | fiche pré-calculée |
| Q6 | « Avons-nous une issue sur le calcul de TVA ? » | XRAGSAND-2 (si Jira branché) | issue XRAGSAND-2 | RAG |
| Q7 | « Avons-nous eu un incident de facturation ? » | oui — TVA à 19,6 % au lieu de 20 % sur les commandes remisées, en mars ; refonte suivie par XRAGSAND-2 | page « Post-mortem incident TVA » | RAG trans-sources |

Critère transverse : chaque réponse **cite ses sources** (fichier/MR/page). Une
réponse juste sans source = échec du critère du cadrage.

🔀 **git local** — Q2 et Q3 ont les **mêmes réponses** (MR-1, puis 2 ouvertes) : les titres
reconstitués sont ceux des commits uniques de chaque branche, choisis égaux aux titres des
MRs GitLab. Différences de forme : MR-1 et MR-2 n'ont pas de numéro (iid 0) et sont citées
par leur id `git-branch:…` ; MR-3 a l'iid 3 — « décris la MR !3 » / `getMergeRequest(3)`
la retrouve, avec un lien `…/passerelle/x-rag-sandbox/fake-billing/-/merge_requests/3`.
Q1, Q4, Q5 ne dépendent pas du transport (même code, mêmes extracteurs).

## 4. Déroulé incrémental

### Étape A — indexation initiale
`./testbed/setup-gitlab.sh init` puis `./bootstrap.sh`. Dérouler §1, §2, §3.

🔀 git local : `./testbed/setup-git-local.sh <dossier> init`, `GIT_REPOS_DIR=<dossier>`
dans `.env`, bloc `sources.git` de `team-config.testbed.yml` à la place de `gitlab:`, puis
`./bootstrap.sh`. Les syncs `gitlab-code` et `gitlab-mr` du dashboard doivent être `OK` —
un miroir illisible met la sync en `ERREUR` et **rien n'est réconcilié** (fail-closed).

### Étape B — nouvelle relation (webhook temps réel)
`./testbed/setup-gitlab.sh increment` — ajoute `PaymentClient.java`
(`@FeignClient(name = "fake-billing")`) à fake-orders, commit direct sur `main`.

- [ ] Sous ~2 min (webhook) ou après un nightly : arête `project:fakeorders -CALLS_API-> project:fakebilling` présente.
- [ ] Q1 mentionne désormais le **4e canal** (orders appelle billing pour l'encaissement).
- [ ] Seuls les chunks du fichier ajouté ont été (ré)embeddés — `updated_at` des autres chunks inchangé.

🔀 git local : `./testbed/setup-git-local.sh <dossier> increment`, puis — pas de webhook —
`curl -X POST -H "X-Admin-Token: $ADMIN_TOKEN" 'localhost:8080/api/admin/sync?source=gitlab-code'`
(ou le nightly). Mêmes cases à cocher : la récolte est complète mais la version d'un
fichier est l'id de son blob, donc seul `PaymentClient.java` est embeddé. Le commit
« feat: … » (sans clé Jira) ne crée **aucune** MR (§2).

### Étape C — suppression (purge des orphelins)
`./testbed/setup-gitlab.sh prune` — supprime `LegacyOrdersWebClient.java`.

- [ ] Après le nightly : plus aucun chunk `rag_chunks` dont `path` contient `LegacyOrdersWebClient`.
- [ ] Aucune arête ni nœud orphelin résiduel lié à ce fichier (réconciliation, étape 6 du batch).

🔀 git local : `./testbed/setup-git-local.sh <dossier> prune`, puis sync `gitlab-code` ou
nightly. Le fichier disparaît de la récolte dès la sync suivante (il n'est plus dans l'arbre
de `main`). ⚠️ Les chunks déjà indexés d'un fichier supprimé ne sont purgés par **aucun**
des deux transports (RUNBOOK, « Basculer API GitLab ↔ dépôts git locaux ») ; le batch ne
purge que les nœuds de graphe sans arête, sans chunk et sans alias — la première case
ci-dessus mesure donc une limite connue, pas une régression propre au mode git.

### Étape D — cycle de vie MR (manuel)
Merger MR-1 dans GitLab.

- [ ] Q2 change de réponse : la plus vieille MR ouverte devient MR-2.
- [ ] `state` de MR-1 passe à `merged` dans la table `merge_requests` (sync `updated_after`).

🔀 git local : `./testbed/setup-git-local.sh <dossier> merge-mr1` — fusionne
`feat/xragsand-1-suivi-commande` dans `main` de fake-orders par un commit de fusion au format
GitLab (« See merge request passerelle/x-rag-sandbox/fake-orders!1 ») et **supprime la
branche**, puis `POST /api/admin/sync?source=gitlab-mr`. Attendu, différent de l'API : la
ligne ouverte `git-branch:fake-orders:feat/xragsand-1-suivi-commande` est **retirée** de
`merge_requests` et du graphe (réconciliation : l'ensemble des branches ouvertes est
autoritaire — seule exception au « upsert only ») et une **nouvelle** ligne
`git:fake-orders:1` apparaît en `merged` (iid 1, `created_at` inchangé = 1er commit de la
branche, `merged_at` = date de la fusion).

- [ ] Q2 → MR-2 ; Q3 → 1 ; `SELECT count(*) FROM merge_requests WHERE id LIKE 'git-branch:%'` = 1.
- [ ] Variante sans suppression de branche (fusionner à la main, garder la branche) : même
      résultat — une branche contenue dans `main` n'est pas « non fusionnée » (§2).

## 5. Boucle d'exploitation

Rejouer ce scénario à chaque évolution du pipeline (nouvel extracteur, changement
de chunking, bump Spring AI...) : c'est le test de non-régression du produit.
Les questions Q1-Q5 sont candidates à la config du smoke test nocturne.
