# Issues du projet Jira factice SAND

> À créer via `testbed/setup-jira.sh` (ou à la main). **Important** : créer dans
> un projet vierge pour que la numérotation commence à SAND-1 — les MRs et pages
> Confluence référencent ces clés littéralement.

## SAND-1 — Suivi de commande dans Fake Orders

- **Type** : Story · **État** : En cours
- **Description** : Les clients veulent suivre l'avancement de leur commande.
  Ajouter un suivi d'état exposé par l'API de Fake Orders et consommé par le
  front. Implémentation en cours dans la MR « SAND-1 Suivi de commande »
  (fake-orders, ouverte — c'est la plus vieille MR ouverte du sandbox).
- **Liens** : relates to SAND-2.

## SAND-2 — Refonte du calcul de TVA

- **Type** : Bug · **État** : En cours
- **Description** : Suite à l'incident de mars (taux 19,6 % appliqué au lieu de
  20 % sur les commandes remisées — voir la page Confluence « Post-mortem
  incident TVA »), refondre le calculateur : appliquer la remise avant
  l'arrondi. Une MR est ouverte sur fake-billing ; elle ne porte volontairement
  PAS la clé SAND-2 dans son titre (piège REFERENCES du scénario, §2).
- **Liens** : relates to SAND-1.

## SAND-3 — Export CSV des factures

- **Type** : Story · **État** : Terminée
- **Description** : La comptabilité a besoin d'un export CSV mensuel des
  factures. Livré par la MR « SAND-3 Export CSV des factures » (fake-billing,
  mergée). C'est cet export qui a révélé l'incident TVA.

## SAND-4 — Afficher le statut de commande dans Fake Front

- **Type** : Story · **État** : À faire
- **Description** : Une fois SAND-1 livré, afficher le statut de commande dans
  la liste des factures de Fake Front (dépend de l'API de suivi de Fake Orders).
- **Liens** : blocks / is blocked by SAND-1.
