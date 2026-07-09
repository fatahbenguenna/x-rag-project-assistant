# Fake Billing — fiche projet

> Page à créer dans le space `XRAGSAND`. Produit `page -DOCUMENTS-> project:fakebilling`
> et `REFERENCES` vers XRAGSAND-2 / XRAGSAND-3.

**Fake Billing** émet et expose les factures du domaine.

## Responsabilités

- Consommation du topic `orders` : chaque commande confirmée déclenche la
  facturation.
- Calcul de TVA — en refonte (XRAGSAND-2), suite à l'incident de mars (voir la page
  Post-mortem incident TVA).
- Export CSV des factures pour la comptabilité — livré (XRAGSAND-3).
- Exposition de l'API `GET /api/invoices`, consommée par Fake Front.

## Dépendances

- API de Fake Orders (Feign) pour le détail des commandes.
- Lecture directe de la table `orders` (dette : à remplacer par l'API, décision
  documentée dans la page Architecture commandes-facturation).
