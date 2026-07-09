#!/usr/bin/env bash
# Crée les pages factices du space Confluence XRAGSAND depuis testbed/confluence/*.md.
#
# Prérequis : curl, jq, pandoc (conversion Markdown -> XHTML storage).
# Le space (clé XRAGSAND par défaut) doit exister. Les lignes de citation « > »
# des fichiers (notes destinées à l'opérateur) sont exclues du contenu publié.
#
# Variables d'environnement :
#   CONFLUENCE_BASE_URL  ex. https://confluence.example.com
#   CONFLUENCE_TOKEN     PAT (Bearer, Data Center) — ou API token Cloud avec CONFLUENCE_USER
#   CONFLUENCE_USER      (optionnel) email/login : bascule en Basic auth (Cloud)
#   CONFLUENCE_SPACE     (optionnel) clé du space, défaut XRAGSAND
set -euo pipefail

: "${CONFLUENCE_BASE_URL:?CONFLUENCE_BASE_URL requis}"
: "${CONFLUENCE_TOKEN:?CONFLUENCE_TOKEN requis}"
SPACE="${CONFLUENCE_SPACE:-XRAGSAND}"
command -v jq >/dev/null || { echo "jq requis" >&2; exit 1; }
command -v pandoc >/dev/null || { echo "pandoc requis (conversion Markdown -> storage). À défaut, coller les pages à la main." >&2; exit 1; }

auth_args() {
  if [ -n "${CONFLUENCE_USER:-}" ]; then printf -- '--user\n%s:%s\n' "$CONFLUENCE_USER" "$CONFLUENCE_TOKEN"
  else printf -- '--header\nAuthorization: Bearer %s\n' "$CONFLUENCE_TOKEN"; fi
}
mapfile -t AUTH < <(auth_args)

DIR="$(cd "$(dirname "$0")" && pwd)/confluence"

for f in "$DIR"/*.md; do
  title=$(sed -n 's/^# //p;q' "$f")
  [ -n "$title" ] || title=$(head -1 "$f" | sed 's/^#\+ *//')
  # corps : sans le H1 (devient le titre) ni les notes opérateur (« > ... »)
  html=$(sed '1{/^# /d}' "$f" | grep -v '^>' | pandoc -f gfm -t html)
  echo "Page « $title »..."
  curl --silent --fail-with-body "${AUTH[@]}" \
    --header 'Content-Type: application/json' \
    --request POST "$CONFLUENCE_BASE_URL/rest/api/content" \
    --data "$(jq -n --arg s "$SPACE" --arg t "$title" --arg b "$html" \
      '{type:"page", title:$t, space:{key:$s}, body:{storage:{value:$b, representation:"storage"}}}')" \
    | jq -r '"  créée : " + ._links.base + ._links.webui'
done

echo "Terminé. Pensez à ajouter les liens entre pages (mentions -> liens Confluence) : ce sont eux qui produisent les arêtes LINKS_TO."
