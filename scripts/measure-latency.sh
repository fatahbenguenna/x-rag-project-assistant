#!/usr/bin/env bash
# Mesure des latences du chat streamé contre les cibles du cadrage (VALIDATION.md §4).
# Usage : API_URL=http://localhost:8080 ./scripts/measure-latency.sh ["question custom"]
set -euo pipefail

API_URL="${API_URL:-http://localhost:8080}"

measure() {
  local label="$1" target="$2" question="$3"
  # time_starttransfer ~ premier token (SSE), time_total = réponse complète
  local out
  out=$(curl -sS -N -o /dev/null \
      -w "%{time_starttransfer} %{time_total}" \
      -H "Content-Type: application/json" \
      -X POST "$API_URL/api/chat" \
      -d "{\"question\": \"$question\"}") || { echo "ERREUR $label : API injoignable"; return 1; }
  local first total
  first=$(echo "$out" | cut -d' ' -f1)
  total=$(echo "$out" | cut -d' ' -f2)
  printf "%-28s 1er token %6.1f s   complet %6.1f s   (cible : %s)\n" "$label" "$first" "$total" "$target"
}

if [ $# -ge 1 ]; then
  measure "custom" "-" "$1"
  exit 0
fi

echo "Mesure des latences sur $API_URL (3 types de question du cadrage)"
echo "NB : lancer 3 fois ; le premier appel après idle peut payer le chargement du modèle."
echo

measure "factuel (tools MRs)"      "10-15 s"          "Quelle merge request ouverte est la plus vieille ?"
measure "résumé projet (fiche)"    "15-25 s, 1er ~5s" "Explique-moi le premier projet configuré en 3 principes."
measure "synthèse trans-projets"   "45-90 s"          "Comment les projets de l'équipe communiquent-ils entre eux ? Donne les flux et les tables partagées."
