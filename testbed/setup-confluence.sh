#!/usr/bin/env bash
# Crée les pages factices du testbed dans un space Confluence EXISTANT où vous
# avez les droits d'écriture — aucun droit admin requis. Les pages sont créées
# sous une page parente « XRAG-SANDBOX » (créée si absente) pour rester
# identifiables d'un coup d'œil.
#
# Recommandé : votre space personnel (clé « ~login »), isolé par nature —
# l'indexation Confluence du RAG se fait PAR SPACE ENTIER, un space partagé
# polluerait la vérité terrain du scénario.
#
# Prérequis : curl, jq, pandoc (conversion Markdown -> XHTML storage).
# Les lignes de citation « > » des fichiers (notes opérateur) sont exclues.
#
# Variables d'environnement :
#   CONFLUENCE_BASE_URL        ex. https://confluence.example.com
#   CONFLUENCE_TOKEN           PAT (Bearer, Data Center) — ou API token Cloud avec CONFLUENCE_USER
#   CONFLUENCE_USER            (optionnel) email/login : bascule en Basic auth (Cloud)
#   CONFLUENCE_SPACE           clé du space existant (ex. ~fbenguenna)
#   CONFLUENCE_PARENT_PAGE_ID  (optionnel) id d'une page parente existante ;
#                              sinon la page « XRAG-SANDBOX » est trouvée ou créée
set -euo pipefail

: "${CONFLUENCE_BASE_URL:?CONFLUENCE_BASE_URL requis}"
: "${CONFLUENCE_TOKEN:?CONFLUENCE_TOKEN requis}"
: "${CONFLUENCE_SPACE:?CONFLUENCE_SPACE requis (space existant où vous pouvez écrire, ex. ~login)}"
command -v jq >/dev/null || { echo "jq requis" >&2; exit 1; }
command -v pandoc >/dev/null || { echo "pandoc requis (conversion Markdown -> storage). À défaut, coller les pages à la main." >&2; exit 1; }

auth_args() {
  if [ -n "${CONFLUENCE_USER:-}" ]; then printf -- '--user\n%s:%s\n' "$CONFLUENCE_USER" "$CONFLUENCE_TOKEN"
  else printf -- '--header\nAuthorization: Bearer %s\n' "$CONFLUENCE_TOKEN"; fi
}
mapfile -t AUTH < <(auth_args)

DIR="$(cd "$(dirname "$0")" && pwd)/confluence"
PARENT_TITLE="XRAG-SANDBOX"

create_page() { # create_page <titre> <html> [parent_id] -> id de la page
  local title=$1 html=$2 parent=${3:-}
  curl --silent --fail-with-body "${AUTH[@]}" \
    --header 'Content-Type: application/json' \
    --request POST "$CONFLUENCE_BASE_URL/rest/api/content" \
    --data "$(jq -n --arg s "$CONFLUENCE_SPACE" --arg t "$title" --arg b "$html" --arg p "$parent" \
      '{type:"page", title:$t, space:{key:$s},
        body:{storage:{value:$b, representation:"storage"}}}
       + (if $p != "" then {ancestors:[{id:($p|tonumber)}]} else {} end)')" \
    | jq -r '.id'
}

# Page parente : fournie, sinon retrouvée par titre, sinon créée à la racine du space.
parent_id="${CONFLUENCE_PARENT_PAGE_ID:-}"
if [ -z "$parent_id" ]; then
  parent_id=$(curl --silent --fail-with-body "${AUTH[@]}" \
    --get "$CONFLUENCE_BASE_URL/rest/api/content" \
    --data-urlencode "spaceKey=$CONFLUENCE_SPACE" \
    --data-urlencode "title=$PARENT_TITLE" \
    | jq -r '.results[0].id // empty')
fi
if [ -z "$parent_id" ]; then
  echo "Création de la page parente « $PARENT_TITLE » dans $CONFLUENCE_SPACE..."
  parent_id=$(create_page "$PARENT_TITLE" \
    "<p>Bac à sable du RAG expérimental — pages factices du testbed x-rag-project-assistant. Ne pas modifier à la main : voir testbed/scenario.md.</p>")
fi
echo "Page parente : id=$parent_id"

for f in "$DIR"/*.md; do
  title=$(sed -n 's/^# //p;q' "$f")
  [ -n "$title" ] || title=$(head -1 "$f" | sed 's/^#\+ *//')
  # corps : sans le H1 (devient le titre) ni les notes opérateur (« > ... »)
  html=$(sed '1{/^# /d}' "$f" | grep -v '^>' | pandoc -f gfm -t html)
  echo "Page « $title »..."
  create_page "$title" "$html" "$parent_id" | sed 's/^/  créée : id=/'
done

echo "Terminé. Pensez à ajouter les liens entre pages (mentions -> liens Confluence) : ce sont eux qui produisent les arêtes LINKS_TO."
