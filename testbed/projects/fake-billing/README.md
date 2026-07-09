# Fake Billing

Service factice de facturation (testbed x-rag). Consomme les événements du topic
Kafka `orders`, appelle l'API de Fake Orders via Feign, et lit la table `orders`
en modèle de lecture.

Contient deux pièges volontaires (`LegacyOrdersWebClient`, MR sans clé Jira) —
voir `testbed/scenario.md` du dépôt principal.
