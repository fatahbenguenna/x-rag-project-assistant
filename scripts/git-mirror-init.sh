#!/usr/bin/env bash
# Crée les miroirs bare des dépôts du mode « dépôts git locaux » (sources.git, sans token
# GitLab) : une URL de clone par ligne dans <repos.list> (modèle : repos.list.example),
# telle que « git clone » l'accepte sur VOTRE poste (SSH, ou HTTPS via Git Credential
# Manager / SSO — aucun credential dans le fichier, aucun dans le conteneur).
#
# Chaque miroir est créé dans <dossier>/<groupe>/<projet>.git (chemin déduit de l'URL :
# git@host:grp/proj.git, https://host/grp/proj.git, ssh://git@host:2222/grp/proj), par
# « git clone --mirror » (toutes les branches, refspec de fetch et remote.origin.mirror=true
# posés par git). Idempotent : un miroir existant est ignoré. Pose gc.autoDetach=false sur
# chaque miroir : un gc détaché réécrirait les packs pendant que l'API lit le dépôt
# (« objet manquant », RUNBOOK §9). Pas de commit ici : commit.gpgsign est sans objet.
#
# Usage : ./scripts/git-mirror-init.sh <repos.list> [dossier]
#   dossier par défaut : GIT_REPOS_DIR (.env), sinon ~/xrag-repos. Chemin ABSOLU dans le FS
#   Linux/WSL (jamais /mnt/c) : c'est lui que docker-compose monte sur /repos:ro.
# Ensuite : GIT_REPOS_DIR=<dossier> dans .env, puis ./scripts/git-fetch-all.sh (premier fetch).
# Code retour : 0 si tous les miroirs sont présents, 1 si un clone a échoué.
set -euo pipefail

[ $# -ge 1 ] && [ $# -le 2 ] || { echo "Usage : $0 <repos.list> [dossier]" >&2; exit 1; }
LIST="$1"
[ -f "$LIST" ] || { echo "ERREUR : $LIST introuvable (modèle : repos.list.example)." >&2; exit 1; }
LIST="$(realpath "$LIST")"

# .env (GIT_REPOS_DIR) — même logique que scripts/check-connections.sh ; racine du dépôt
# quel que soit le dossier courant (l'argument <repos.list> a déjà été résolu).
ROOT_DIR="$(cd "$(dirname "$0")/.." && pwd)"
if [ -f "$ROOT_DIR/.env" ]; then set -a; . "$ROOT_DIR/.env"; set +a; fi

DIR="${2:-${GIT_REPOS_DIR:-$HOME/xrag-repos}}"
case "$DIR" in
  /*) ;;
  *) echo "ERREUR : dossier « $DIR » non absolu — docker-compose exige un chemin absolu (ex. /home/<vous>/xrag-repos)." >&2; exit 1 ;;
esac
case "$DIR" in
  /mnt/*) echo "AVERTISSEMENT : $DIR est sous /mnt (DrvFs) — lent et fragile ; préférer le FS WSL (ex. ~/xrag-repos)." >&2 ;;
esac
# Racine trop large (même règle que l'API, GitRepositories.isDangerousRoot) : système, home d'un
# utilisateur, lecteur Windows monté — le contenu deviendrait interrogeable via le chat.
dangerous_root() { # dangerous_root <chemin> — code retour 0 si refusé
  local p; p=$(printf '%s' "$1" | tr '\\' '/' | sed -E 's#/+$##'); [ -n "$p" ] || p=/
  [[ "$p" =~ ^(/|/(home|root|Users|mnt|tmp|etc|var|usr|opt|app|config|srv|media|proc|sys)|/(home|Users)/[^/]+|/mnt/[^/]+|/mnt/[^/]+/Users(/[^/]+)?|[A-Za-z]:|[A-Za-z]:/Users(/[^/]+)?)$ ]]
}
if dangerous_root "$DIR"; then
  echo "ERREUR : $DIR est une racine trop large (home, disque entier…) — l'API la refuse : choisir un dossier DÉDIÉ, ex. /home/<vous>/xrag-repos." >&2; exit 1
fi
mkdir -p "$DIR"
DIR="$(realpath "$DIR")"

# Chemin distant grp/sub/proj déduit d'une URL de clone (même règle que l'API : dernier
# segment = nom technique du projet). Une URL locale (file://, chemin absolu) n'a pas de
# groupe : <projet>.git directement sous le dossier.
remote_path() { # remote_path <url> — affiche grp/sub/proj (sans .git), vide si illisible
  local url=$1 path
  case "$url" in
    file://*)   path=${url#file://} ; path=${path##*/} ;;
    /* | ./* | ../*) path=${url##*/} ;;
    *://*)      path=${url#*://}; path=${path#*/} ;;      # scheme://[user@]host[:port]/grp/proj
    *@*:* | *:*) path=${url#*:} ;;                           # [user@]host:grp/proj (forme scp)
    *)          path="" ;;
  esac
  path=${path%%/}; path=${path%.git}; path=${path#/}
  case "$path" in *..*) path="" ;; esac
  printf '%s' "$path"
}

echo "== Miroirs git ($LIST -> $DIR) =="
created=0; skipped=0; failed=0
while IFS= read -r line || [ -n "$line" ]; do
  url=$(printf '%s' "$line" | sed -e 's/[[:space:]]*#.*$//' -e 's/^[[:space:]]*//' -e 's/[[:space:]]*$//')
  [ -n "$url" ] || continue
  path=$(remote_path "$url")
  if [ -z "$path" ]; then
    echo "  [KO] URL illisible : $url"; failed=$((failed + 1)); continue
  fi
  target="$DIR/$path.git"
  depth=$(printf '%s' "$path" | awk -F/ '{ print NF }')
  if [ -d "$target" ] && git --git-dir="$target" rev-parse --is-bare-repository > /dev/null 2>&1; then
    echo "  [--] $path.git déjà présent — ignoré"
    git --git-dir="$target" config gc.autoDetach false
    skipped=$((skipped + 1))
  else
    if [ -e "$target" ]; then
      echo "  [KO] $path.git existe mais n'est pas un dépôt bare — déplacer ou supprimer ce dossier"
      failed=$((failed + 1)); continue
    fi
    echo "  ==> git clone --mirror -> $path.git"
    mkdir -p "$(dirname "$target")"
    # Le clone peut être long (historique complet) et interactif (SSO, clé SSH) : pas de timeout.
    if git clone --mirror "$url" "$target"; then
      git --git-dir="$target" config gc.autoDetach false
      echo "  [OK] $path.git"; created=$((created + 1))
    else
      echo "  [KO] $path.git — clone en échec (credentials ? git ls-remote --heads <url> pour tester)"
      rm -rf "$target"; failed=$((failed + 1)); continue   # jamais un dossier préexistant (testé ci-dessus)
    fi
  fi
  if [ "$depth" -gt 4 ]; then
    echo "       NB : profondeur $depth > 4 (limite de la découverte automatique de l'API) — à lister dans sources.git.repos : « $path.git »"
  fi
done < "$LIST"

echo "== Résultat : $created créé(s), $skipped déjà présent(s), $failed en échec =="
if [ $((created + skipped)) -gt 0 ]; then
  echo "Suite : GIT_REPOS_DIR=$DIR dans .env, puis ./scripts/git-fetch-all.sh (premier fetch, journal $DIR/fetch.log)."
fi
[ "$failed" -eq 0 ]
