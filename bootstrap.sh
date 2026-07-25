#!/usr/bin/env bash
# Indexation initiale d'une nouvelle instance (à lancer après `docker compose up -d`).
# Peut durer 3 à 6 h la première nuit selon le volume — laisser tourner.
set -euo pipefail

API_URL="${API_URL:-http://localhost:8080}"
OLLAMA_CONTAINER="${OLLAMA_CONTAINER:-xrag-ollama}"
CHAT_MODEL="${CHAT_MODEL:-qwen2.5:7b-instruct}"
EMBEDDING_MODEL="${EMBEDDING_MODEL:-bge-m3}"

echo "==> Téléchargement des modèles Ollama ($CHAT_MODEL, $EMBEDDING_MODEL)"
docker exec "$OLLAMA_CONTAINER" ollama pull "$CHAT_MODEL"
docker exec "$OLLAMA_CONTAINER" ollama pull "$EMBEDDING_MODEL"

echo "==> Attente de l'API ($API_URL)"
for i in $(seq 1 60); do
  if curl -sf "$API_URL/actuator/health" >/dev/null; then break; fi
  [ "$i" = 60 ] && { echo "L'API ne répond pas — vérifier docker compose logs rag-api" >&2; exit 1; }
  sleep 5
done
echo "    API prête."

echo "==> Lancement de l'indexation initiale complète"
# Reranker cross-encoder (optionnel : retrieval.reranker.enabled dans team-config.yml).
# Modèle épinglé sur un commit HF (reproductible) — fichier ONNX int8 UNIQUE + tokenizer.
if grep -qE '^\s*enabled:\s*true' team-config.yml 2>/dev/null && grep -q 'reranker:' team-config.yml; then
  RR_REPO="onnx-community/bge-reranker-v2-m3-ONNX"
  RR_REV="6f5ff65298512715a1e669753bc754d2bc8f367b"
  RR_BASE="https://huggingface.co/${RR_REPO}/resolve/${RR_REV}"
  echo "Téléchargement du modèle reranker (571 Mo, une seule fois)…"
  docker compose exec -T rag-api sh -c '
    set -e; mkdir -p /models/reranker; cd /models/reranker
    [ -f model_quantized.onnx ] || curl -fL "'"$RR_BASE"'/onnx/model_quantized.onnx" -o model_quantized.onnx
    [ -f tokenizer.json ] || curl -fL "'"$RR_BASE"'/tokenizer.json" -o tokenizer.json
  ' || echo "Téléchargement reranker en échec — l'app fonctionnera sans (passe-plat)."
fi

# ADMIN_TOKEN (si configuré) protège les POST /api/admin/**
ADMIN_HEADER=()
[ -n "${ADMIN_TOKEN:-}" ] && ADMIN_HEADER=(-H "X-Admin-Token: $ADMIN_TOKEN")
curl -sf -X POST ${ADMIN_HEADER[@]+"${ADMIN_HEADER[@]}"} "$API_URL/api/admin/sync?full=true" >/dev/null

echo "==> Indexation en cours. Suivi (Ctrl-C pour arrêter le suivi, l'indexation continue) :"
previous=""
while true; do
  sleep 60
  current="$(curl -sf "$API_URL/api/admin/status" || true)"
  echo "    $(date '+%H:%M') $current"
  if [ -n "$current" ] && [ "$current" = "$previous" ] && [ "$current" != "{}" ]; then
    echo "==> Compteurs stables : indexation initiale probablement terminée."
    break
  fi
  previous="$current"
done

echo "==> Smoke test"
curl -sf -X POST ${ADMIN_HEADER[@]+"${ADMIN_HEADER[@]}"} "$API_URL/api/admin/smoke-test" || echo "Smoke test en échec — voir les logs rag-api"

echo "==> Terminé. Poser une question : curl -N -X POST $API_URL/api/chat -H 'Content-Type: application/json' -d '{\"question\":\"...\"}'"
