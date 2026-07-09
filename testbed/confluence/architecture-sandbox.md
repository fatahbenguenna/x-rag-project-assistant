# Architecture du domaine commandes-facturation (page à créer dans le space SAND)

> Copier ce contenu dans une page Confluence du space factice `SAND`, titre :
> « Architecture commandes-facturation ».

Le domaine repose sur deux services : **Fake Orders** gère le cycle de vie des
commandes et publie chaque événement sur le topic Kafka `orders` ; **Fake Billing**
consomme ces événements pour émettre les factures, et interroge l'API de
fake-orders pour le détail des commandes (voir SAND-1).

Le front **Fake Front** affiche les factures via l'API de Fake Billing.

Arêtes attendues après indexation (scenario.md §1) : cette page `DOCUMENTS`
fakeorders, fakebilling et fakefront, et `REFERENCES` l'issue SAND-1.
