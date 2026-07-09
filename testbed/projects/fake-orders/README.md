# Fake Orders

Service factice de gestion des commandes (testbed x-rag). Publie les événements
de commande sur le topic Kafka `orders`, possède la table `orders` (lue aussi par
Fake Billing).

Relations attendues dans le graphe : voir `testbed/scenario.md` du dépôt principal.
