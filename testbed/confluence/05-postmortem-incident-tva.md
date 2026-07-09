# Post-mortem — incident TVA de mars

> Page à créer dans le space `SAND`. Permet de tester les questions type
> « avons-nous eu un bug sur X ? » avec une réponse connue.

## Résumé

Du 3 au 5 mars, les factures émises par **Fake Billing** appliquaient un taux de
TVA erroné (19,6 % au lieu de 20 %) sur les commandes contenant une remise.

## Chronologie

- J1 : écart signalé par la comptabilité (export CSV, SAND-3).
- J2 : cause identifiée — arrondi effectué avant application de la remise dans
  le calculateur de TVA.
- J3 : correctif temporaire ; la refonte complète du calcul est suivie par
  **SAND-2** (MR « Refonte du calcul de TVA » ouverte sur fake-billing).

## Actions

- [x] Correctif temporaire en production.
- [ ] Refonte du calculateur (SAND-2).
- [ ] Test de non-régression sur les commandes remisées.
