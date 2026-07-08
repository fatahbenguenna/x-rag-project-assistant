# Validation terrain

Checklist de mise en service sur la machine de référence (Ryzen 7, 32 Go RAM,
inférence CPU) avec de **vraies** instances Confluence/GitLab/Jira. À dérouler
une fois l'onboarding du README terminé ; chaque étape a un critère de succès
mesurable. Cette validation n'a **pas encore été exécutée** — les latences du
cadrage restent théoriques tant que ce runbook n'a pas été déroulé.

## 1. Démarrage de la pile

```bash
docker compose up -d
docker exec xrag-ollama ollama pull qwen2.5:7b-instruct
docker exec xrag-ollama ollama pull qwen2.5:3b        # fallback descriptif
docker exec xrag-ollama ollama pull bge-m3
curl -s http://localhost:8080/actuator/health          # attendu : {"status":"UP"}
```

- [ ] Health UP, logs Liquibase sans erreur (`docker logs xrag-api | grep -i liquibase`).

## 2. Indexation initiale

```bash
./bootstrap.sh          # 3-6 h la première nuit selon le volume
```

- [ ] Compteurs en croissance pendant l'indexation : `curl -s localhost:8080/api/admin/status`
- [ ] À la fin : chunks > 0 pour chaque source configurée (confluence, gitlab-code, jira),
      table MRs alimentée.

## 3. Qualité du graphe (décision d'architecture n°10)

```bash
curl -s http://localhost:8080/api/admin/graph-quality
```

- [ ] `verdict` sans trou, **ou** liste de trous à traiter :
  - « chunks non rattachés » → vérifier les extracteurs activés (`extractors.*`) ;
  - « nœuds orphelins » → compléter la table d'alias (`aliases` du team-config) ;
  - « projets sans relation structurante » → vérifier que le code des projets
    est bien indexé (branches, extensions).
- [ ] Si les trous persistent après correction des alias/extracteurs :
      c'est le signal factuel pour ouvrir le chantier « extraction LLM nocturne ».

## 4. Latences (cadrage validé)

```bash
./scripts/measure-latency.sh                 # API_URL=http://host:8080 pour un poste distant
```

| Type de question | Cible | Mesuré |
|---|---|---|
| Factuel via tools (MRs) | 10-15 s | ☐ |
| Résumé projet (fiche pré-calculée) | 15-25 s complet, 1er token ~5 s | ☐ |
| Synthèse trans-projets A×B | 45-90 s streamé | ☐ |

- [ ] Trois exécutions par type (le premier appel après idle peut payer le
      chargement du modèle — c'est le rôle du warm-up 07:30 / `OLLAMA_KEEP_ALIVE=24h`).
- [ ] Si le résumé projet dépasse 25 s : vérifier que les fiches projet existent
      (`fiche` dans `api/admin/status`, régénérées par le batch, étape 8).

## 5. Batch nocturne et notifications

- [ ] Déclencher manuellement : `curl -X POST localhost:8080/api/admin/nightly` —
      terminé < 45 min, notification de fin reçue (webhook `NOTIFY_WEBHOOK_URL`
      ou logs), rapport smoke test inclus, verdict d'éval graphe inclus.
- [ ] Couper Postgres puis redéclencher : alerte « batch abandonné » reçue,
      l'API continue de répondre sur l'index existant.
- [ ] Laisser tourner une nuit complète : batch 02:00 OK, warm-up 07:30 visible
      dans les logs.

## 6. Temps réel GitLab

- [ ] Configurer le webhook GitLab (push + merge_request, secret
      `GITLAB_WEBHOOK_TOKEN`) vers `https://<instance>/api/webhooks/gitlab`.
- [ ] Pousser un commit sur un repo du groupe : le fichier modifié est
      réinterrogeable dans les ~2 min (question sur le contenu du commit).
- [ ] Ouvrir/mettre à jour une MR : visible via « quelle est la dernière MR ? ».

## 7. UI (optionnel)

- [ ] `docker compose --profile ui up -d` → Open WebUI sur `:3000` voit le
      modèle `xrag-<team>` (endpoint `/v1`) et streame les réponses.

## 8. Critères d'acceptation finaux

- [ ] Les 4 questions types du cadrage répondent correctement avec sources citées.
- [ ] Onboarding total (hors indexation) < 1 h chrono.
- [ ] `docker compose pull && up -d` avec une image versionnée (`RAG_API_IMAGE`)
      migre la base sans perte d'index.
