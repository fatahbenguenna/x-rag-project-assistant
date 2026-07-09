# Scénario de validation — vérité terrain

Ce document est le contrat du testbed : ce que l'index **doit** contenir, ce qu'il
**ne doit pas** contenir (pièges), et les questions dont la réponse est connue.

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
| `page:* -DOCUMENTS-> project:*` | les 5 pages du space XRAGSAND mentionnent les projets (fiches 02/03, architecture 01, runbook 04, post-mortem 05) | extraction Confluence + alias |
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

## 2. Pièges — ce que l'index NE doit PAS contenir

| Piège | Fichier | Comportement attendu |
|---|---|---|
| Topic Kafka dynamique (`"audit-" + region`) | `AuditTrailRouter.java` | **aucune** arête `PUBLISHES` vers `topic:audit-*` — limite connue de l'extraction déterministe |
| Appel HTTP via `WebClient` (pas Feign) | `LegacyOrdersWebClient.java` | **aucune** arête `CALLS_API` supplémentaire depuis fake-billing |
| MR sans clé Jira | MR-2 « Refonte du calcul de TVA » | arêtes `MODIFIES` présentes, **aucune** `REFERENCES` |
| Alias mal orthographié « Fake Ordres » | `confluence/04-runbook-facturation.md` | mention **non résolue** tant que l'alias n'est pas déclaré ; ajoutez ensuite `"Fake Ordres"` aux alias de `fakeorders`, re-synchronisez, et vérifiez que l'arête `DOCUMENTS` apparaît — c'est le test de la table d'alias |

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

## 4. Déroulé incrémental

### Étape A — indexation initiale
`./testbed/setup-gitlab.sh init` puis `./bootstrap.sh`. Dérouler §1, §2, §3.

### Étape B — nouvelle relation (webhook temps réel)
`./testbed/setup-gitlab.sh increment` — ajoute `PaymentClient.java`
(`@FeignClient(name = "fake-billing")`) à fake-orders, commit direct sur `main`.

- [ ] Sous ~2 min (webhook) ou après un nightly : arête `project:fakeorders -CALLS_API-> project:fakebilling` présente.
- [ ] Q1 mentionne désormais le **4e canal** (orders appelle billing pour l'encaissement).
- [ ] Seuls les chunks du fichier ajouté ont été (ré)embeddés — `updated_at` des autres chunks inchangé.

### Étape C — suppression (purge des orphelins)
`./testbed/setup-gitlab.sh prune` — supprime `LegacyOrdersWebClient.java`.

- [ ] Après le nightly : plus aucun chunk `rag_chunks` dont `path` contient `LegacyOrdersWebClient`.
- [ ] Aucune arête ni nœud orphelin résiduel lié à ce fichier (réconciliation, étape 6 du batch).

### Étape D — cycle de vie MR (manuel)
Merger MR-1 dans GitLab.

- [ ] Q2 change de réponse : la plus vieille MR ouverte devient MR-2.
- [ ] `state` de MR-1 passe à `merged` dans la table `merge_requests` (sync `updated_after`).

## 5. Boucle d'exploitation

Rejouer ce scénario à chaque évolution du pipeline (nouvel extracteur, changement
de chunking, bump Spring AI...) : c'est le test de non-régression du produit.
Les questions Q1-Q5 sont candidates à la config du smoke test nocturne.
