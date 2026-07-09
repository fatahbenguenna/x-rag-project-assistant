# Fake Orders — fiche projet

> Page à créer dans le space `XRAGSAND`. Sert de source documentaire pour les
> questions descriptives (Q5-like) et produit `page -DOCUMENTS-> project:fakeorders`.

**Fake Orders** (aussi appelé `FAKEORDERS` dans les anciens documents) est le
service référent des commandes.

## Responsabilités

- Création et suivi des commandes clients (suivi enrichi en cours : XRAGSAND-1).
- Publication des événements de commande sur le topic Kafka `orders`
  (contrat : une clé = la référence commande, un événement par transition d'état).
- Propriété de la table `orders` (PostgreSQL).

## API

`GET /api/orders/{id}` — détail d'une commande, consommé par Fake Billing.

## Points d'attention

L'audit réglementaire est routé vers des topics régionaux (`audit-<région>`),
construits dynamiquement — voir le code de `AuditTrailRouter`.
