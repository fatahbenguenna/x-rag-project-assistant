# Runbook facturation

> Page à créer dans le space `XRAGSAND`. Contient le PIÈGE d'alias du scénario (§2).

En cas d'écart de facturation :

1. Vérifier la consommation du topic `orders` par Fake Billing (lag consumer).
2. Vérifier le calcul de TVA — chantier de refonte en cours, voir XRAGSAND-2.
3. En dernier recours, comparer avec la table `orders` (modèle de lecture).

Historiquement, les commandes étaient récupérées par le client legacy de
**Fake Ordres** avant la mise en place du client Feign.

> ⚠️ PIÈGE VOLONTAIRE : « Fake Ordres » (orthographe erronée) ne doit PAS être
> résolu vers `project:fakeorders` tant que cet alias n'est pas déclaré dans le
> team-config. L'ajouter ensuite, re-synchroniser, et vérifier que l'arête
> DOCUMENTS apparaît — c'est le test de la table d'alias (scenario.md §2).
