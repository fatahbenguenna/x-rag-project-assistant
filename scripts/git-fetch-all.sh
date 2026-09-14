#!/usr/bin/env bash
# Rafraîchit tous les dépôts du mode « dépôts git locaux » (sources.git) puis, avec --sync,
# demande à l'API de ré-indexer le code et les MRs. C'est LA commande unique à planifier
# (01:15 avant le batch de 02:00, puis toutes les 2 h en journée — RUNBOOK §8) : l'application
# ne fait JAMAIS de git fetch (aucun credential dans le conteneur), la fraîcheur de l'index
# est celle de ces passages.
#
# Pour chaque dépôt sous GIT_REPOS_DIR (profondeur 4, de <dossier>/<projet> à
# <dossier>/<grp>/<sous-grp>/<sous-sous-grp>/<projet>, bare/mirror ou clone de travail — même
# règle que l'API, GitRepositories.MAX_DEPTH) :
#   timeout 300 git remote update --prune   (bare/mirror)   ou   git fetch --all --prune   (clone)
#   puis git -c gc.autoDetach=false gc --auto  (gc TERMINÉ avant que l'API relise les packs)
# Verrou flock sur le dossier (<dossier>/.fetch.lock) : deux passages ne se chevauchent pas.
# Journal horodaté <dossier>/fetch.log ; <dossier>/.last-fetch écrit en fin de passage réussi.
# Code retour ≠ 0 si un dépôt a échoué (les autres sont quand même rafraîchis et synchronisés).
#
# --sync : charge .env puis POST /api/admin/sync?source=gitlab-code (code) et
#          ?source=gitlab-mr (MRs) sur ${API_URL:-http://localhost:8080}, header X-Admin-Token
#          si ADMIN_TOKEN est défini. Les deux syncs sont asynchrones : résultat sur le dashboard.
#
# Usage : ./scripts/git-fetch-all.sh [--sync]     (GIT_REPOS_DIR : .env ou environnement)
set -euo pipefail

cd "$(dirname "$0")/.."

SYNC=false
case "${1:-}" in
  --sync) SYNC=true ;;
  "") ;;
  *) echo "Usage : $0 [--sync]" >&2; exit 1 ;;
esac

# .env : GIT_REPOS_DIR (toujours), ADMIN_TOKEN + API_URL (--sync) — même logique que bootstrap.sh
if [ -f .env ]; then set -a; . ./.env; set +a; fi
DIR="${GIT_REPOS_DIR:-}"
[ -n "$DIR" ] || { echo "ERREUR : GIT_REPOS_DIR non renseigné (.env) — dossier des miroirs créés par scripts/git-mirror-init.sh." >&2; exit 1; }
[ -d "$DIR" ] || { echo "ERREUR : GIT_REPOS_DIR=$DIR introuvable." >&2; exit 1; }
DIR="$(realpath "$DIR")"
LOG="$DIR/fetch.log"

# Tâche planifiée : jamais de question au terminal (credentials via GCM/SSO ou clé SSH ;
# sinon l'erreur sort tout de suite dans fetch.log au lieu de bloquer jusqu'au timeout).
export GIT_TERMINAL_PROMPT=0

log() { printf '%s %s\n' "$(date '+%Y-%m-%d %H:%M:%S')" "$*" | tee -a "$LOG"; }
# Les sorties git peuvent citer l'URL du remote : la partie user:token@ n'est jamais journalisée.
mask() { sed -E 's#(://)[^/@[:space:]]+@#\1***@#g'; }

# Dépôt git ? clone de travail (.git dossier, ou fichier « gitdir: » d'un worktree lié) ou bare.
repo_kind() { # repo_kind <dossier> — affiche clone | bare | rien
  local d=$1
  if [ -d "$d/.git" ] || [ -f "$d/.git" ]; then echo clone
  elif [ "$(git --git-dir="$d" rev-parse --is-bare-repository 2> /dev/null)" = true ]; then echo bare
  fi
}
# Découverte profondeur 4 (GitRepositories.MAX_DEPTH) : symlinks et dossiers cachés ignorés
# (comme l'API) ; un dépôt trouvé n'est pas parcouru (refs/, objects/ ne sont pas des dépôts).
discover() { # discover <dossier> <profondeur> — un « <kind> <dossier> » par ligne
  local d kind
  for d in "$1"/*/; do
    d=${d%/}
    [ -d "$d" ] && [ ! -L "$d" ] || continue
    kind=$(repo_kind "$d")
    if [ -n "$kind" ]; then echo "$kind $d"
    elif [ "$2" -lt 4 ]; then discover "$d" $(($2 + 1)); fi
  done
}

# Verrou : un passage à la fois (tâche planifiée + lancement manuel). On attend celui en
# cours (15 min max) plutôt que d'ignorer la demande — un run manuel doit faire son travail.
exec 9> "$DIR/.fetch.lock"
flock -w 900 9 || { echo "ERREUR : un autre passage tient $DIR/.fetch.lock depuis plus de 15 min." >&2; exit 1; }

log "== Fetch de tous les dépôts sous $DIR =="
ok=0; failed=0
while read -r kind d; do
  [ -n "$d" ] || continue
  rel=${d#"$DIR"/}
  if [ "$kind" = bare ]; then
    cmd=(git -C "$d" remote update --prune)
  else
    cmd=(git -C "$d" fetch --all --prune)
  fi
  if out=$(timeout 300 "${cmd[@]}" 2>&1); then
    if gc_out=$(git -C "$d" -c gc.autoDetach=false gc --auto 2>&1); then
      log "  [OK] $rel ($kind)"; ok=$((ok + 1))
    else
      log "  [KO] $rel ($kind) : gc --auto en échec — $(printf '%s' "$gc_out" | mask | tail -n 3 | tr '\n' ' ')"
      failed=$((failed + 1))
    fi
  else
    rc=$?
    reason=$( [ "$rc" -eq 124 ] && echo "timeout 300 s" || echo "code $rc" )
    log "  [KO] $rel ($kind) : fetch en échec ($reason) — $(printf '%s' "$out" | mask | tail -n 3 | tr '\n' ' ')"
    failed=$((failed + 1))
  fi
done < <(discover "$DIR" 1)

if [ $((ok + failed)) -eq 0 ]; then
  log "ERREUR : aucun dépôt git sous $DIR (profondeur 4 : <projet>.git … <grp>/<sous-grp>/<sous-sous-grp>/<projet>.git) — scripts/git-mirror-init.sh repos.list $DIR"
  exit 1
fi
if [ "$failed" -eq 0 ]; then
  date -Iseconds > "$DIR/.last-fetch"
  log "== $ok dépôt(s) à jour, .last-fetch écrit =="
else
  log "== $ok dépôt(s) à jour, $failed en ÉCHEC (.last-fetch non mis à jour) =="
fi

if [ "$SYNC" = true ]; then
  API_URL="${API_URL:-http://localhost:8080}"
  # ADMIN_TOKEN (si configuré) protège les POST /api/admin/**
  ADMIN_HEADER=()
  [ -n "${ADMIN_TOKEN:-}" ] && ADMIN_HEADER=(-H "X-Admin-Token: $ADMIN_TOKEN")
  for source in gitlab-code gitlab-mr; do
    code=$(curl -s -m 15 -o /dev/null -w '%{http_code}' -X POST ${ADMIN_HEADER[@]+"${ADMIN_HEADER[@]}"} \
      "$API_URL/api/admin/sync?source=$source" 2> /dev/null || true)
    case "$code" in
      2*) log "  [OK] sync $source lancée ($API_URL) — suivi : dashboard / GET $API_URL/api/admin/status" ;;
      401 | 403) log "  [KO] sync $source : HTTP $code — ADMIN_TOKEN manquant ou invalide dans .env"; failed=$((failed + 1)) ;;
      000 | "") log "  [KO] sync $source : rag-api injoignable sur $API_URL (docker compose up -d ?)"; failed=$((failed + 1)) ;;
      *) log "  [KO] sync $source : HTTP $code"; failed=$((failed + 1)) ;;
    esac
  done
fi

[ "$failed" -eq 0 ]
