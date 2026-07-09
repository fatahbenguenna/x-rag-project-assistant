# Runbook facturation (page à créer dans le space SAND)

> Copier ce contenu dans une page Confluence du space factice `SAND`, titre :
> « Runbook facturation ».

En cas d'écart de facturation, vérifier d'abord la consommation du topic `orders`
par Fake Billing, puis le calcul de TVA (chantier en cours, voir SAND-2).

Historiquement, les commandes étaient récupérées par le client legacy de
**Fake Ordres** — PIÈGE VOLONTAIRE (scenario.md §2) : cette orthographe erronée
ne doit PAS être résolue vers `project:fakeorders` tant que l'alias « Fake Ordres »
n'a pas été ajouté au team-config. L'ajouter ensuite et re-synchroniser pour
vérifier la table d'alias.
