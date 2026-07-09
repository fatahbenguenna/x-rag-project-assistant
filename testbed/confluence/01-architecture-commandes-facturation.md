# Architecture commandes-facturation

> Page à créer sous la page parente « XRAG-SANDBOX » (space de votre choix) (titre identique au H1). Ajoutez des liens
> Confluence vers les pages « Fake Orders » et « Fake Billing » sur leurs
> mentions : ce sont eux qui produisent les arêtes LINKS_TO entre pages.

Le domaine repose sur deux services et un front :

- **Fake Orders** gère le cycle de vie des commandes. Chaque changement d'état
  est publié sur le topic Kafka `orders`. La table `orders` lui appartient.
- **Fake Billing** consomme le topic `orders` pour émettre les factures. Pour le
  détail d'une commande, il appelle l'API REST de fake-orders (client Feign,
  chantier XRAGSAND-1 en cours pour enrichir ce flux). Il lit aussi la table
  `orders` en modèle de lecture — dette assumée, voir la page Runbook facturation.
- **Fake Front** affiche les factures via l'API de Fake Billing.

Décision d'architecture : la communication nominale est **événementielle**
(Kafka) ; les appels synchrones (Feign) sont réservés aux lectures ponctuelles.
