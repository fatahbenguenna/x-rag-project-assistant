#!/usr/bin/env bash
# Crée les issues factices du projet Jira SAND (voir jira/issues-sandbox.md).
#
# Prérequis : curl, jq. Le projet (clé SAND par défaut) doit exister et être
# VIERGE : les MRs et pages Confluence référencent SAND-1..4 littéralement.
#
# Variables d'environnement :
#   JIRA_BASE_URL   ex. https://jira.example.com
#   JIRA_TOKEN      PAT (Bearer, Data Center) — ou API token Cloud avec JIRA_USER
#   JIRA_USER       (optionnel) email/login : bascule en Basic auth (Cloud)
#   JIRA_PROJECT    (optionnel) clé du projet, défaut SAND
set -euo pipefail

: "${JIRA_BASE_URL:?JIRA_BASE_URL requis}"
: "${JIRA_TOKEN:?JIRA_TOKEN requis}"
PROJECT="${JIRA_PROJECT:-SAND}"
command -v jq >/dev/null || { echo "jq requis" >&2; exit 1; }

auth_args() {
  if [ -n "${JIRA_USER:-}" ]; then printf -- '--user\n%s:%s\n' "$JIRA_USER" "$JIRA_TOKEN"
  else printf -- '--header\nAuthorization: Bearer %s\n' "$JIRA_TOKEN"; fi
}
mapfile -t AUTH < <(auth_args)

create_issue() { # create_issue <type> <summary> <description> -> clé créée
  curl --silent --fail-with-body "${AUTH[@]}" \
    --header 'Content-Type: application/json' \
    --request POST "$JIRA_BASE_URL/rest/api/2/issue" \
    --data "$(jq -n --arg p "$PROJECT" --arg t "$1" --arg s "$2" --arg d "$3" \
      '{fields:{project:{key:$p}, issuetype:{name:$t}, summary:$s, description:$d}}')" \
    | jq -r '.key'
}

link_issues() { # link_issues <type> <inward-key> <outward-key>
  curl --silent --fail-with-body "${AUTH[@]}" \
    --header 'Content-Type: application/json' \
    --request POST "$JIRA_BASE_URL/rest/api/2/issueLink" \
    --data "$(jq -n --arg t "$1" --arg i "$2" --arg o "$3" \
      '{type:{name:$t}, inwardIssue:{key:$i}, outwardIssue:{key:$o}}')" > /dev/null
}

k1=$(create_issue Story "Suivi de commande dans Fake Orders" \
  "Ajouter un suivi d'état des commandes exposé par l'API de Fake Orders et consommé par le front. Implémentation en cours dans la MR « SAND-1 Suivi de commande » (fake-orders).")
k2=$(create_issue Bug "Refonte du calcul de TVA" \
  "Suite à l'incident de mars (19,6 % au lieu de 20 % sur les commandes remisées, voir la page « Post-mortem incident TVA ») : appliquer la remise avant l'arrondi. La MR associée sur fake-billing ne porte volontairement pas cette clé (piège du scénario).")
k3=$(create_issue Story "Export CSV des factures" \
  "Export CSV mensuel des factures pour la comptabilité. Livré par la MR « SAND-3 Export CSV des factures » (fake-billing, mergée).")
k4=$(create_issue Story "Afficher le statut de commande dans Fake Front" \
  "Afficher le statut de commande dans la liste des factures de Fake Front, une fois le suivi de Fake Orders livré.")
echo "Issues créées : $k1 $k2 $k3 $k4"

link_issues "Relates" "$k1" "$k2"
link_issues "Blocks"  "$k1" "$k4"
echo "Liens créés : $k1 relates $k2 ; $k1 blocks $k4"

[ "$k1" = "$PROJECT-1" ] || echo "ATTENTION : clés != $PROJECT-1..4 (projet non vierge ?) — les REFERENCES des MRs/pages ne matcheront pas." >&2
echo "À faire à la main (workflows spécifiques à l'instance) : passer $k1 et $k2 « En cours », $k3 « Terminée »."
