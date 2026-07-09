#!/usr/bin/env bash
# Crée le sandbox GitLab du testbed et joue les étapes incrémentales de scenario.md.
#
# Prérequis : curl, jq, git. Variables d'environnement :
#   GITLAB_BASE_URL      ex. https://gitlab.example.com
#   GITLAB_TOKEN         PAT scope api
#   GITLAB_PARENT_GROUP  chemin du groupe parent (ex. passerelle) — le sous-groupe
#                        xrag-sandbox est créé dessous
#
# Usage : ./setup-gitlab.sh init | increment | prune
set -euo pipefail

: "${GITLAB_BASE_URL:?GITLAB_BASE_URL requis}"
: "${GITLAB_TOKEN:?GITLAB_TOKEN requis}"
: "${GITLAB_PARENT_GROUP:?GITLAB_PARENT_GROUP requis}"
command -v jq >/dev/null || { echo "jq requis" >&2; exit 1; }

API="$GITLAB_BASE_URL/api/v4"
AUTH=(--header "PRIVATE-TOKEN: $GITLAB_TOKEN")
SANDBOX_PATH="$GITLAB_PARENT_GROUP/xrag-sandbox"
TESTBED_DIR="$(cd "$(dirname "$0")" && pwd)"
PROJECTS=(fake-orders fake-billing fake-front)

api() { # api METHOD PATH [curl args...]
  local method=$1 path=$2; shift 2
  curl --silent --fail-with-body "${AUTH[@]}" --request "$method" "$API$path" "$@"
}

urlencode() { jq -rn --arg v "$1" '$v|@uri'; }

group_id() { api GET "/groups/$(urlencode "$1")" | jq -r '.id'; }

project_id() { api GET "/projects/$(urlencode "$SANDBOX_PATH/$1")" | jq -r '.id'; }

push_dir() { # push_dir <dir> <project> — pousse le contenu comme main
  local dir=$1 project=$2 tmp
  tmp=$(mktemp -d)
  cp -r "$dir"/. "$tmp/"
  git -C "$tmp" init -q -b main
  git -C "$tmp" add -A
  git -C "$tmp" -c user.name=testbed -c user.email=testbed@example.com \
    commit -qm "chore: état initial du testbed"
  git -C "$tmp" push -q \
    "https://oauth2:$GITLAB_TOKEN@${GITLAB_BASE_URL#https://}/$SANDBOX_PATH/$project.git" main
  rm -rf "$tmp"
}

commit_file() { # commit_file <project> <repo-path> <local-file|-> <message> [branch]
  local project=$1 path=$2 local_file=$3 message=$4 branch=${5:-main} content
  content=$(base64 -w0 < "$local_file")
  api POST "/projects/$(project_id "$project")/repository/commits" \
    --header 'Content-Type: application/json' \
    --data "$(jq -n --arg b "$branch" --arg m "$message" --arg p "$path" --arg c "$content" \
      '{branch:$b, commit_message:$m, actions:[{action:"create", file_path:$p, content:$c, encoding:"base64"}]}')" \
    > /dev/null
}

create_mr() { # create_mr <project> <source-branch> <title> <description>
  local project=$1 branch=$2 title=$3 desc=$4 pid
  pid=$(project_id "$project")
  api POST "/projects/$pid/repository/branches?branch=$branch&ref=main" > /dev/null
  api POST "/projects/$pid/merge_requests" \
    --header 'Content-Type: application/json' \
    --data "$(jq -n --arg s "$branch" --arg t "$title" --arg d "$desc" \
      '{source_branch:$s, target_branch:"main", title:$t, description:$d}')" \
    | jq -r '.web_url'
}

case "${1:-}" in
  init)
    parent_id=$(group_id "$GITLAB_PARENT_GROUP")
    echo "Création du sous-groupe $SANDBOX_PATH..."
    api POST "/groups" --data "name=xrag-sandbox&path=xrag-sandbox&parent_id=$parent_id" > /dev/null \
      || echo "(sous-groupe déjà existant, on continue)"
    sandbox_id=$(group_id "$SANDBOX_PATH")

    for p in "${PROJECTS[@]}"; do
      echo "Projet $p..."
      api POST "/projects" --data "name=$p&path=$p&namespace_id=$sandbox_id&initialize_with_readme=false" > /dev/null \
        || echo "($p déjà existant, on continue)"
      push_dir "$TESTBED_DIR/projects/$p" "$p"
    done

    echo "MRs (l'ordre de création fixe l'ancienneté — MR-1 = la plus vieille)..."
    # MR-1 (ouverte, avec clé Jira) : réponse attendue de « la MR ouverte la plus vieille »
    b1=feat/xragsand-1-suivi-commande
    api POST "/projects/$(project_id fake-orders)/repository/branches?branch=$b1&ref=main" > /dev/null
    printf '// XRAGSAND-1 : suivi de commande (en cours)\n' > /tmp/track.java.tmp
    commit_file fake-orders "src/main/java/com/sandbox/orders/OrderTracking.java" /tmp/track.java.tmp \
      "XRAGSAND-1 ébauche du suivi de commande" "$b1"
    api POST "/projects/$(project_id fake-orders)/merge_requests" \
      --header 'Content-Type: application/json' \
      --data "$(jq -n --arg s "$b1" '{source_branch:$s, target_branch:"main",
        title:"XRAGSAND-1 Suivi de commande", description:"Implémente le suivi demandé par XRAGSAND-1."}')" | jq -r '.web_url'

    # MR-2 (ouverte, SANS clé Jira — piège REFERENCES)
    b2=feat/refonte-tva
    api POST "/projects/$(project_id fake-billing)/repository/branches?branch=$b2&ref=main" > /dev/null
    printf '// refonte du calcul de TVA (en cours)\n' > /tmp/tva.java.tmp
    commit_file fake-billing "src/main/java/com/sandbox/billing/VatCalculator.java" /tmp/tva.java.tmp \
      "refonte du calcul de TVA" "$b2"
    api POST "/projects/$(project_id fake-billing)/merge_requests" \
      --header 'Content-Type: application/json' \
      --data "$(jq -n --arg s "$b2" '{source_branch:$s, target_branch:"main",
        title:"Refonte du calcul de TVA", description:"Sans référence Jira — volontaire (piège du scénario)."}')" | jq -r '.web_url'

    # MR-3 (mergée, avec clé Jira) : teste l'état merged dans la table MR
    b3=feat/xragsand-3-export-csv
    api POST "/projects/$(project_id fake-billing)/repository/branches?branch=$b3&ref=main" > /dev/null
    printf '// XRAGSAND-3 : export CSV des factures\n' > /tmp/csv.java.tmp
    commit_file fake-billing "src/main/java/com/sandbox/billing/InvoiceCsvExporter.java" /tmp/csv.java.tmp \
      "XRAGSAND-3 export CSV des factures" "$b3"
    mr3_iid=$(api POST "/projects/$(project_id fake-billing)/merge_requests" \
      --header 'Content-Type: application/json' \
      --data "$(jq -n --arg s "$b3" '{source_branch:$s, target_branch:"main",
        title:"XRAGSAND-3 Export CSV des factures", description:"Ferme XRAGSAND-3."}')" | jq -r '.iid')
    sleep 2
    api PUT "/projects/$(project_id fake-billing)/merge_requests/$mr3_iid/merge" > /dev/null
    echo "MR-3 mergée."
    rm -f /tmp/track.java.tmp /tmp/tva.java.tmp /tmp/csv.java.tmp
    echo "Sandbox prêt : $GITLAB_BASE_URL/$SANDBOX_PATH"
    ;;

  increment)
    # Étape B du scénario : nouvelle arête CALLS_API fake-orders -> fake-billing
    commit_file fake-orders "src/main/java/com/sandbox/orders/PaymentClient.java" \
      "$TESTBED_DIR/increments/PaymentClient.java" \
      "feat: encaissement via fake-billing (nouvelle relation CALLS_API)"
    echo "PaymentClient.java poussé sur fake-orders/main — vérifier l'arête sous ~2 min (webhook) ou après le nightly."
    ;;

  prune)
    # Étape C du scénario : suppression -> purge des chunks/nœuds orphelins au nightly
    api POST "/projects/$(project_id fake-billing)/repository/commits" \
      --header 'Content-Type: application/json' \
      --data '{"branch":"main","commit_message":"chore: suppression du client legacy",
        "actions":[{"action":"delete","file_path":"src/main/java/com/sandbox/billing/LegacyOrdersWebClient.java"}]}' \
      > /dev/null
    echo "LegacyOrdersWebClient.java supprimé — vérifier la purge après le prochain nightly (scenario.md §4C)."
    ;;

  *)
    echo "Usage : $0 init | increment | prune" >&2
    exit 1
    ;;
esac
