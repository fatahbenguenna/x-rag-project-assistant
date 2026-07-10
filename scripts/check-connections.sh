#!/usr/bin/env bash
# Préflight avant bootstrap : teste chaque connexion (Postgres, Ollama, GitLab,
# Confluence, Jira, rag-api) avec les mêmes credentials et la même logique
# d'authentification que l'application (cookie > basic > bearer, cf. .env.example).
#
# Usage : ./scripts/check-connections.sh   (depuis la racine, .env + team-config.yml présents)
# Code retour : 0 si tout passe, 1 sinon.
set -uo pipefail

cd "$(dirname "$0")/.."

# --- Chargement .env + team-config.yml ------------------------------------
if [ -f .env ]; then set -a; . ./.env; set +a; else
  echo "AVERTISSEMENT : pas de .env — variables prises dans l'environnement courant." >&2
fi
CONFIG="${TEAM_CONFIG:-./team-config.yml}"
[ -f "$CONFIG" ] || { echo "ERREUR : $CONFIG introuvable (copier team-config.example.yml)." >&2; exit 1; }

yaml_value() { # yaml_value <section> <clé> — valeur d'une clé sous sources.<section> ou llm
  awk -v sec="$1" -v key="$2" '
    $1 == sec ":" { in_sec = 1; next }
    in_sec && /^  [a-zA-Z]/ { in_sec = 0 }
    in_sec && $1 == key ":" { sub(/^[^:]*: */, ""); sub(/ *#.*$/, ""); gsub(/["\x27\[\]]/, ""); print; exit }
  ' "$CONFIG"
}

first_of_list() { echo "${1%%,*}" | tr -d ' '; }

pass=0; fail=0
ok() { printf '  [OK] %s\n' "$1"; pass=$((pass + 1)); }
ko() { printf '  [KO] %s — %s\n' "$1" "$2"; fail=$((fail + 1)); }

# --- Vérification HTTP générique -------------------------------------------
http_json() { # http_json <label> <motif attendu> <url> [args curl...]
  local label=$1 pattern=$2 url=$3; shift 3
  local out code body
  out=$(curl -sS -m 15 -w $'\n%{http_code}' "$@" "$url" 2>&1) || { ko "$label" "injoignable : ${out:0:100}"; return; }
  code=${out##*$'\n'}; body=${out%$'\n'*}
  if [ "$code" = 200 ] && printf '%s' "$body" | grep -q "$pattern"; then ok "$label"; return; fi
  case "$code" in
    401 | 403) ko "$label" "HTTP $code — authentification refusée (token/cookie expiré ou invalide ?)" ;;
    404)       ko "$label" "HTTP 404 — vérifier le base-url (context path ?) ou la clé espace/projet" ;;
    200)       ko "$label" "200 mais contenu inattendu (page HTML de SSO ?) : ${body:0:80}" ;;
    *)         ko "$label" "HTTP $code : ${body:0:80}" ;;
  esac
}

# Même priorité que SourceAuth : cookie > basic (user défini) > bearer.
auth_args() { # auth_args <COOKIE> <USER> <TOKEN> — imprime les args curl, un par ligne
  local cookie=$1 user=$2 token=$3
  if [ -n "$cookie" ]; then printf -- '--header\nCookie: %s\n' "$cookie"
  elif [ -n "$user" ]; then printf -- '--user\n%s:%s\n' "$user" "$token"
  else printf -- '--header\nAuthorization: Bearer %s\n' "$token"; fi
}

echo "== Préflight x-rag ($CONFIG) =="

# --- Postgres ----------------------------------------------------------------
if command -v docker > /dev/null && docker ps --format '{{.Names}}' 2> /dev/null | grep -q '^xrag-postgres$'; then
  if docker exec xrag-postgres pg_isready -U "${POSTGRES_USER:-xrag}" -d "${POSTGRES_DB:-xrag}" > /dev/null 2>&1; then
    ok "Postgres (pg_isready)"
  else ko "Postgres" "pg_isready en échec — voir docker compose logs postgres"; fi
else
  echo "  [--] Postgres ignoré (conteneur xrag-postgres non démarré — docker compose up -d ?)"
fi

# --- Ollama + modèles ----------------------------------------------------------
OLLAMA_URL="${OLLAMA_BASE_URL:-http://localhost:11434}"
chat_model=$(yaml_value llm model); chat_model=${chat_model:-qwen2.5:7b-instruct}
emb_model=$(yaml_value llm embedding-model); emb_model=${emb_model:-bge-m3}
tags=$(curl -sS -m 10 "$OLLAMA_URL/api/tags" 2>&1)
if printf '%s' "$tags" | grep -q '"models"'; then
  ok "Ollama ($OLLAMA_URL)"
  printf '%s' "$tags" | grep -q "$chat_model" && ok "Modèle chat $chat_model" \
    || ko "Modèle chat $chat_model" "absent — docker exec xrag-ollama ollama pull $chat_model"
  printf '%s' "$tags" | grep -q "$emb_model" && ok "Modèle embeddings $emb_model" \
    || ko "Modèle embeddings $emb_model" "absent — docker exec xrag-ollama ollama pull $emb_model"
else
  ko "Ollama ($OLLAMA_URL)" "injoignable : ${tags:0:80}"
fi

# --- GitLab -------------------------------------------------------------------
gitlab_url=$(yaml_value gitlab base-url)
if [ -n "$gitlab_url" ]; then
  group=$(yaml_value gitlab group)
  http_json "GitLab groupe $group" '"id"' \
    "$gitlab_url/api/v4/groups/$(printf '%s' "$group" | sed 's|/|%2F|g')" \
    --header "PRIVATE-TOKEN: ${GITLAB_TOKEN:-}"
fi

# --- Confluence -----------------------------------------------------------------
confluence_url=$(yaml_value confluence base-url)
if [ -n "$confluence_url" ]; then
  mapfile -t CAUTH < <(auth_args "${CONFLUENCE_COOKIE:-}" "${CONFLUENCE_USER:-}" "${CONFLUENCE_TOKEN:-}")
  space=$(first_of_list "$(yaml_value confluence spaces)")
  http_json "Confluence space $space" '"results"' \
    "$confluence_url/rest/api/content?spaceKey=$space&limit=1" "${CAUTH[@]}"
fi

# --- Jira ------------------------------------------------------------------------
jira_url=$(yaml_value jira base-url)
if [ -n "$jira_url" ]; then
  mapfile -t JAUTH < <(auth_args "${JIRA_COOKIE:-}" "${JIRA_USER:-}" "${JIRA_TOKEN:-}")
  http_json "Jira identité (myself)" '"name"\|"displayName"' \
    "$jira_url/rest/api/2/myself" "${JAUTH[@]}"
  project=$(first_of_list "$(yaml_value jira projects)")
  http_json "Jira projet $project" '"key"' \
    "$jira_url/rest/api/2/project/$project" "${JAUTH[@]}"
fi

# --- rag-api (optionnel : seulement si la pile tourne) --------------------------
API_URL="${API_URL:-http://localhost:8080}"
if curl -sf -m 5 "$API_URL/actuator/health" 2> /dev/null | grep -q '"UP"'; then
  ok "rag-api ($API_URL/actuator/health)"
else
  echo "  [--] rag-api non démarré (normal avant docker compose up — le bootstrap l'attendra)"
fi

echo "== Résultat : $pass OK, $fail KO =="
[ "$fail" -eq 0 ]
