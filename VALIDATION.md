# Validation terrain

Checklist de mise en service sur la machine de référence (Ryzen 7, 32 Go RAM,
inférence CPU) avec de **vraies** instances Confluence/GitLab/Jira. À dérouler
une fois l'onboarding du README terminé ; chaque étape a un critère de succès
mesurable. Cette validation n'a **pas encore été exécutée** — les latences du
cadrage restent théoriques tant que ce runbook n'a pas été déroulé.

## 1. Démarrage de la pile

Pile démarrée et modèles tirés — README, onboarding étapes 4-5 (y compris
`qwen2.5:3b` si `fallback-model` est conservé dans `team-config.yml` : le préflight
ne vérifie pas ce modèle).

- [ ] Health UP : `curl -s http://localhost:8080/actuator/health` → `{"status":"UP"}` ;
      logs Liquibase sans erreur (`docker logs xrag-api | grep -i liquibase`).
- [ ] Préflight tout vert : `./scripts/check-connections.sh` (README étape 6).

## 2. Indexation initiale

Bootstrap lancé — README étape 7 (3-6 h la première nuit selon le volume).

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
- [ ] Si les trous persistent après correction des alias/extracteurs : c'est le signal
      factuel pour **activer l'enrichissement LLM nocturne** — `extractors.llm: true`
      dans `team-config.yml`, ou déclenchement manuel `POST /api/admin/enrich`
      (RUNBOOK §7) — puis re-vérifier `graph-quality`.

## 4. Latences (cibles = README « Performances attendues »)

```bash
./scripts/measure-latency.sh                 # API_URL=http://host:8080 pour un poste distant
```

| Type de question | Cible | Mesuré |
|---|---|---|
| Factuel via tools (MRs) | 10-15 s | ☐ |
| Résumé projet (fiche pré-calculée) | 15-25 s complet, 1er token ~5 s | ☐ |
| Synthèse trans-projets A×B | 45-90 s streamé | ☐ |

- [ ] Trois exécutions par type (le premier appel après idle peut payer le chargement
      du modèle — cf. warm-up/keep-alive, README et RUNBOOK §9).
- [ ] Si le résumé projet dépasse 25 s : vérifier que les fiches projet existent —
      clé `chunks.project-sheet` > 0 dans `GET /api/admin/status` (régénérées par le
      batch, étape 8).

## 5. Batch nocturne et notifications

- [ ] Déclencher manuellement (RUNBOOK §7 — header `X-Admin-Token` requis si
      `ADMIN_TOKEN` est configuré) :
      `curl -X POST -H "X-Admin-Token: $ADMIN_TOKEN" localhost:8080/api/admin/nightly` —
      terminé < 45 min, notification de fin reçue (webhook `NOTIFY_WEBHOOK_URL`
      ou logs), rapport smoke test inclus, verdict d'éval graphe inclus.
- [ ] Couper Postgres puis redéclencher : alerte « batch abandonné » reçue,
      l'API continue de répondre sur l'index existant.
- [ ] Laisser tourner une nuit complète : batch 02:00 OK, warm-up 07:30 visible
      dans les logs.

## 6. Temps réel GitLab

Webhook configuré au préalable — procédure : RUNBOOK §8 « Temps réel GitLab ».

- [ ] Pousser un commit sur un repo du groupe : le fichier modifié est
      réinterrogeable dans les ~2 min (question sur le contenu du commit).
- [ ] Ouvrir/mettre à jour une MR : visible via « quelle est la dernière MR ? ».

## 7. UI (optionnel)

- [ ] Ajouter `ui` à `COMPOSE_PROFILES` (`.env`) puis `docker compose up -d` →
      Open WebUI sur `:3000` voit le modèle `xrag-<team>` (endpoint `/v1`) et
      streame les réponses.

## 8. Critères d'acceptation finaux

- [ ] Les 4 questions types du cadrage répondent correctement avec sources citées.
- [ ] Onboarding total (hors indexation) < 1 h chrono.
- [ ] `docker compose pull && up -d` avec une image versionnée (`RAG_API_IMAGE`)
      migre la base sans perte d'index.
