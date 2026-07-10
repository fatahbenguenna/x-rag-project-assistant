# Fake Front

Front factice (TypeScript/Angular minimal, testbed x-rag). Affiche les factures
en appelant l'API de Fake Billing via `HttpClient` et l'URL d'environnement
`environment.billingUrl`.

Relation attendue : `project:fakefront -CALLS_API-> project:fakebilling`
(voir `testbed/scenario.md` du dépôt principal).
