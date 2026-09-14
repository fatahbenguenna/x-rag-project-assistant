#!/usr/bin/env bash
# Préflight avant bootstrap : teste chaque connexion (Postgres, Ollama, GitLab,
# Confluence, Jira, rag-api) avec les mêmes credentials et la même logique
# d'authentification que l'application : cookie > oauth > basic > scoped > bearer
# (cf. .env.example). Les modes scoped/oauth routent par api.atlassian.com.
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
getvar() { eval "printf '%s' \"\${$1:-}\""; }   # lecture d'une variable par nom (robuste à set -u)

pass=0; fail=0
ok() { printf '  [OK] %s\n' "$1"; pass=$((pass + 1)); }
ko() { printf '  [KO] %s — %s\n' "$1" "$2"; fail=$((fail + 1)); }
warn() { printf '  [!!] %s — %s\n' "$1" "$2"; }   # signalé, ne compte pas comme KO

# --- Vérification HTTP générique -------------------------------------------
http_json() { # http_json <label> <motif attendu> <url> [args curl...]
  local label=$1 pattern=$2 url=$3; shift 3
  local out code body
  out=$(curl -sS -m 15 -w $'\n%{http_code}' "$@" "$url" 2>&1) || { ko "$label" "injoignable : ${out:0:100}"; return; }
  code=${out##*$'\n'}; body=${out%$'\n'*}
  if [ "$code" = 200 ] && printf '%s' "$body" | grep -q "$pattern"; then ok "$label"; return; fi
  case "$code" in
    401 | 403) ko "$label" "HTTP $code — authentification refusée (token/cookie/scopes ?)" ;;
    404)       ko "$label" "HTTP 404 — vérifier le base-url (context path ?) ou la clé espace/projet" ;;
    200)       ko "$label" "200 mais contenu inattendu (page HTML de SSO ?) : ${body:0:80}" ;;
    *)         ko "$label" "HTTP $code : ${body:0:80}" ;;
  esac
}

# cloudId d'un site Atlassian via l'endpoint public {origin}/_edge/tenant_info
tenant_cloud_id() { # tenant_cloud_id <base-url>
  local origin
  origin=$(printf '%s' "$1" | sed -E 's#(https?://[^/]+).*#\1#')
  curl -sS -m 15 "$origin/_edge/tenant_info" 2> /dev/null \
    | sed -n 's/.*"cloudId"[^"]*"\([^"]*\)".*/\1/p'
}

# access token OAuth 2.0 « client credentials » (compte de service Cloud)
oauth_access_token() { # oauth_access_token <client_id> <client_secret>
  curl -sS -m 15 -X POST 'https://auth.atlassian.com/oauth/token' \
    --data-urlencode "client_id=$1" \
    --data-urlencode "client_secret=$2" \
    --data-urlencode 'grant_type=client_credentials' 2> /dev/null \
    | sed -n 's/.*"access_token"[^"]*"\([^"]*\)".*/\1/p'
}

# Résout, comme l'application, (mode, base API effective, args curl d'auth) pour une
# source Atlassian. Priorité : cookie > oauth > basic > scoped > bearer. Résultat dans
# les globales RA_MODE, RA_BASE, RA_AUTH (tableau d'arguments curl).
resolve_atlassian() { # resolve_atlassian <prefix> <base-url> <produit: confluence|jira>
  local prefix=$1 base=$2 product=$3
  local token user cookie cid secret suffix cloudid access
  token=$(getvar "${prefix}_TOKEN");  user=$(getvar "${prefix}_USER")
  cookie=$(getvar "${prefix}_COOKIE")
  cid=$(getvar "${prefix}_OAUTH_CLIENT_ID"); secret=$(getvar "${prefix}_OAUTH_CLIENT_SECRET")
  suffix=""; [ "$product" = confluence ] && suffix="/wiki"
  if printf '%s' "$base" | grep -q '\.atlassian\.net'; then RA_PLATFORM=cloud; else RA_PLATFORM=datacenter; fi
  RA_AUTH=()

  if [ -n "$cookie" ]; then
    RA_MODE=cookie; RA_BASE=$base; RA_AUTH=(--header "Cookie: $cookie")
  elif [ -n "$cid" ] && [ -n "$secret" ]; then
    RA_MODE=oauth
    cloudid=$(tenant_cloud_id "$base"); access=$(oauth_access_token "$cid" "$secret")
    RA_BASE="https://api.atlassian.com/ex/$product/$cloudid$suffix"
    RA_AUTH=(--header "Authorization: Bearer $access")
  elif [ -n "$user" ]; then
    RA_MODE=basic; RA_BASE=$base; RA_AUTH=(--user "$user:$token")
  elif [ -n "$token" ] && [ "$RA_PLATFORM" = cloud ]; then
    RA_MODE=scoped
    cloudid=$(tenant_cloud_id "$base")
    RA_BASE="https://api.atlassian.com/ex/$product/$cloudid$suffix"
    RA_AUTH=(--header "Authorization: Bearer $token")
  else
    RA_MODE=bearer; RA_BASE=$base; RA_AUTH=(--header "Authorization: Bearer $token")
  fi
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
# Ce préflight tourne sur l'hôte : le nom de service Docker « ollama » n'y est pas
# résoluble. On cible le port publié sur localhost (cf. `ports` du docker-compose).
OLLAMA_URL=$(printf '%s' "$OLLAMA_URL" | sed 's#//ollama:#//localhost:#')
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

# --- Git local (sources.git : miroirs du poste montés sur /repos:ro, lus via JGit) ------
# Reproduit les contrôles faits par l'API à l'ouverture des dépôts (racine dangereuse,
# découverte profondeur 4, branches cibles, clone partiel/shallow, âge du dernier fetch)
# AVANT le premier sync — côté hôte, avec le binaire git du poste.
git_root=$(yaml_value git root)
if [ -n "$git_root" ]; then
  git_dir="${GIT_REPOS_DIR:-}"
  git_remote=$(yaml_value git remote); git_remote=${git_remote:-origin}
  git_branches=$(yaml_value git branches | tr ',' ' '); git_branches=${git_branches:-main}
  git_repos=$(yaml_value git repos | tr ',' ' ')
  [ "$git_root" = /repos ] || warn "sources.git.root = $git_root" "docker-compose monte GIT_REPOS_DIR sur /repos"
  if [ -z "$git_dir" ]; then
    ko "GIT_REPOS_DIR" "non renseigné dans .env — Docker monterait ./repos VIDE (scripts/git-mirror-init.sh puis .env)"
  elif grep -qE '^GIT_REPOS_DIR=~' .env 2> /dev/null; then
    ko "GIT_REPOS_DIR=~…" "docker compose n'interprète pas « ~ » : chemin ABSOLU Linux (ex. /home/<vous>/xrag-repos)"
  elif [ "${git_dir#/}" = "$git_dir" ]; then
    ko "GIT_REPOS_DIR=$git_dir" "chemin relatif — docker-compose exige un chemin absolu"
  elif [ ! -d "$git_dir" ]; then
    ko "GIT_REPOS_DIR=$git_dir" "dossier introuvable — scripts/git-mirror-init.sh repos.list $git_dir"
  else
    git_real=$(readlink -f "$git_dir")
    case "$git_dir" in /mnt/*) warn "GIT_REPOS_DIR sous /mnt" "DrvFs lent et fragile — préférer le FS WSL (ex. ~/xrag-repos)" ;; esac
    git_forbidden=false
    for forbidden in / /home /root /mnt /mnt/c /tmp /etc /var /usr /opt /app /config; do
      [ "$git_real" = "$forbidden" ] && git_forbidden=true
    done
    if [ "$git_forbidden" = true ]; then
      ko "GIT_REPOS_DIR=$git_real" "racine refusée par l'API (exposerait tout le FS au conteneur) — dossier DÉDIÉ aux miroirs"
    else
      ok "GIT_REPOS_DIR=$git_dir (absolu, dédié)"
      # Dépôt git ? clone de travail (.git dossier ou fichier « gitdir: ») ou bare — règle de l'API
      git_kind() { # git_kind <dossier> — affiche clone | bare | rien
        if [ -d "$1/.git" ] || [ -f "$1/.git" ]; then echo clone
        elif [ "$(git --git-dir="$1" rev-parse --is-bare-repository 2> /dev/null)" = true ]; then echo bare; fi
      }
      git_found=()
      if [ -n "$git_repos" ]; then                    # sources.git.repos : périmètre explicite
        for sub in $git_repos; do
          if [ -d "$git_real/$sub" ] && [ -n "$(git_kind "$git_real/$sub")" ]; then git_found+=("$git_real/$sub")
          else ko "sources.git.repos « $sub »" "absent ou pas un dépôt git sous $git_dir — ignoré par l'API"; fi
        done
      else                                            # découverte auto profondeur 4 (GitRepositories.MAX_DEPTH)
        git_scan() { # git_scan <dossier> <profondeur> — symlinks et dossiers cachés ignorés, un dépôt n'est pas parcouru
          local d
          for d in "$1"/*/; do
            d=${d%/}
            [ -d "$d" ] && [ ! -L "$d" ] || continue
            if [ -n "$(git_kind "$d")" ]; then git_found+=("$d")
            elif [ "$2" -lt 4 ]; then git_scan "$d" $(($2 + 1)); fi
          done
        }
        git_scan "$git_real" 1
      fi
      if [ ${#git_found[@]} -eq 0 ]; then
        ko "Dépôts git sous $git_dir" "aucun (profondeur 4 : <projet>.git … <grp>/<sous-grp>/<sous-sous-grp>/<projet>.git, pas de symlink) — scripts/git-mirror-init.sh"
      fi
      for d in ${git_found[@]+"${git_found[@]}"}; do
        rel=${d#"$git_real"/}; kind=$(git_kind "$d"); repo_ok=true
        gitdir=$(git -C "$d" rev-parse --absolute-git-dir 2> /dev/null || echo "$d")
        for b in $git_branches; do
          git -C "$d" rev-parse --verify -q "refs/remotes/$git_remote/$b" > /dev/null 2>&1 \
            || git -C "$d" rev-parse --verify -q "refs/heads/$b" > /dev/null 2>&1 \
            || { ko "Dépôt $rel : branche $b" "ni refs/remotes/$git_remote/$b ni refs/heads/$b — branche absente ou jamais fetchée"; repo_ok=false; }
        done
        if [ "$(git -C "$d" config --get "remote.$git_remote.mirror" 2> /dev/null)" != true ] \
           && [ -z "$(git -C "$d" config --get "remote.$git_remote.fetch" 2> /dev/null)" ]; then
          ko "Dépôt $rel : remote $git_remote" "sans refspec de fetch (clone --bare au lieu de --mirror ?) — git-fetch-all.sh ne le rafraîchira pas"
          repo_ok=false
        fi
        if [ "$(git -C "$d" config --get extensions.partialclone 2> /dev/null)" = true ] \
           || [ -n "$(git -C "$d" config --get "remote.$git_remote.promisor" 2> /dev/null)" ]; then
          ko "Dépôt $rel" "clone PARTIEL (--filter) refusé par l'API (JGit ne récupère pas les objets manquants) — recloner en miroir complet"
          repo_ok=false
        fi
        [ -f "$gitdir/shallow" ] && warn "Dépôt $rel" "clone SHALLOW (--depth) : historique tronqué, MRs anciennes irrécupérables — git fetch --unshallow"
        fetch_age=""
        if [ -f "$gitdir/FETCH_HEAD" ]; then
          fetch_age=$(( ($(date +%s) - $(stat -c %Y "$gitdir/FETCH_HEAD")) / 3600 ))
          [ "$fetch_age" -ge 24 ] && warn "Dépôt $rel" "dernier fetch il y a $fetch_age h — miroir périmé ? scripts/git-fetch-all.sh --sync (planification : RUNBOOK §8)"
        else
          warn "Dépôt $rel" "jamais fetché depuis le clone (pas de FETCH_HEAD) — scripts/git-fetch-all.sh"
        fi
        [ "$repo_ok" = true ] && ok "Dépôt $rel ($kind, branches : $(echo $git_branches)${fetch_age:+, fetch il y a $fetch_age h})"
      done
      # Montage prouvé depuis le conteneur (seulement si la pile tourne)
      if command -v docker > /dev/null && docker ps --format '{{.Names}}' 2> /dev/null | grep -q '^xrag-api$'; then
        mounted=$(docker compose exec -T rag-api ls "$git_root" 2> /dev/null | wc -l)
        if [ "$mounted" -gt 0 ]; then ok "Montage $git_root dans rag-api ($mounted entrée(s))"
        else ko "Montage $git_root dans rag-api" "vide — GIT_REPOS_DIR changé après démarrage ? docker compose up -d rag-api (recrée le conteneur)"; fi
      else
        echo "  [--] Montage $git_root non vérifié (conteneur xrag-api non démarré)"
      fi
    fi
  fi
fi

# --- Confluence -----------------------------------------------------------------
confluence_url=$(yaml_value confluence base-url)
if [ -n "$confluence_url" ]; then
  resolve_atlassian CONFLUENCE "$confluence_url" confluence
  space=$(first_of_list "$(yaml_value confluence spaces)")
  if [ "$RA_PLATFORM" = cloud ]; then
    http_json "Confluence space $space [$RA_MODE/cloud]" '"results"' \
      "$RA_BASE/api/v2/spaces?keys=$space&limit=1" "${RA_AUTH[@]}"
  else
    http_json "Confluence space $space [$RA_MODE/dc]" '"results"' \
      "$RA_BASE/rest/api/content?spaceKey=$space&limit=1" "${RA_AUTH[@]}"
  fi
fi

# --- Jira ------------------------------------------------------------------------
jira_url=$(yaml_value jira base-url)
if [ -n "$jira_url" ]; then
  resolve_atlassian JIRA "$jira_url" jira
  project=$(first_of_list "$(yaml_value jira projects)")
  if [ "$RA_PLATFORM" = cloud ]; then
    http_json "Jira identité (myself) [$RA_MODE/cloud]" '"name"\|"displayName"\|"accountId"' \
      "$RA_BASE/rest/api/3/myself" "${RA_AUTH[@]}"
    http_json "Jira recherche $project [$RA_MODE/cloud]" '"issues"' \
      "$RA_BASE/rest/api/3/search/jql?jql=project%20in%20($project)&maxResults=1" "${RA_AUTH[@]}"
  else
    http_json "Jira identité (myself) [$RA_MODE/dc]" '"name"\|"displayName"\|"accountId"' \
      "$RA_BASE/rest/api/2/myself" "${RA_AUTH[@]}"
    http_json "Jira recherche $project [$RA_MODE/dc]" '"issues"' \
      "$RA_BASE/rest/api/2/search?jql=project%20in%20($project)&maxResults=1" "${RA_AUTH[@]}"
  fi
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
