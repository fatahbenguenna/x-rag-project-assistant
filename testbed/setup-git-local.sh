#!/usr/bin/env bash
# Variante « dépôts git locaux » (sources.git, sans GitLab) du testbed : reconstruit le
# sandbox de scenario.md en dépôts git LOCAUX et joue les étapes incrémentales.
#
# Même scénario que setup-gitlab.sh (mêmes projets, branches, fichiers, ordre de création =
# ancienneté), mais sans API : les MRs n'existent que sous la forme que le connecteur
# LocalGitConnector sait RECONSTITUER depuis l'historique —
#   MR-1  fake-orders   branche feat/xragsand-1-suivi-commande NON fusionnée -> MR ouverte présumée
#                       (la plus vieille : dates de commit croissantes)
#   MR-2  fake-billing  branche feat/refonte-tva NON fusionnée, sans clé Jira  -> MR ouverte présumée
#   MR-3  fake-billing  fusionnée dans main par un commit de fusion au format GitLab exact
#                       (« See merge request passerelle/x-rag-sandbox/fake-billing!3 ») -> MR merged, iid 3
#
# Disposition produite (celle d'un « git clone --mirror » : <groupe>/<projet>.git) :
#   <dossier>/x-rag-sandbox/fake-orders.git | fake-billing.git | fake-front.git   (dépôts bare)
# Le contenu vient de testbed/projects/* ; l'historique est construit dans un clone de travail
# TEMPORAIRE puis poussé dans le bare. Chaque miroir porte un remote « origin » FACTICE
# ($SANDBOX_REMOTE_BASE/<projet>.git) : c'est de cette URL que l'API déduit le slug du projet
# (fake-orders), son chemin complet (passerelle/x-rag-sandbox/fake-orders) et les liens web —
# elle n'est jamais contactée (l'API ne fetch jamais). NE PAS lancer scripts/git-fetch-all.sh
# sur ce dossier : le remote n'existe pas, les étapes ci-dessous poussent directement.
#
# Prérequis : git >= 2.28. Aucun token, aucun réseau. <dossier> = la valeur à mettre dans
# GIT_REPOS_DIR (.env) — chemin absolu, dossier DÉDIÉ (jamais /tmp, /home... : refusés par l'API).
#
# Usage : ./setup-git-local.sh <dossier> init | increment | prune | merge-mr1
set -euo pipefail

TESTBED_DIR="$(cd "$(dirname "$0")" && pwd)"
PROJECTS=(fake-orders fake-billing fake-front)
SANDBOX_REMOTE_BASE="${SANDBOX_REMOTE_BASE:-https://gitlab.example.com/passerelle/x-rag-sandbox}"
# Dates fixes et croissantes : createdAt d'une MR reconstituée = 1er commit de sa branche,
# mergedAt = date committer du commit de fusion -> MR-1 est la plus vieille, reproductible.
D_INIT="2026-01-05T09:00:00+01:00"
D_MR1="2026-01-06T10:00:00+01:00"
D_MR2="2026-01-07T10:00:00+01:00"
D_MR3="2026-01-08T10:00:00+01:00"
D_MR3_MERGE="2026-01-08T11:00:00+01:00"

usage() { echo "Usage : $0 <dossier> init | increment | prune | merge-mr1" >&2; exit 1; }
[ $# -eq 2 ] || usage
ROOT_ARG=$1 ACTION=$2
case "$ROOT_ARG" in /*) ;; *) echo "<dossier> doit être un chemin ABSOLU (il devient GIT_REPOS_DIR)" >&2; exit 1 ;; esac
for forbidden in / /home /root /mnt /mnt/c /tmp /etc /var /usr /opt /app /config; do
  [ "${ROOT_ARG%/}" = "$forbidden" ] && { echo "$ROOT_ARG : racine refusée par l'API — choisir un dossier DÉDIÉ" >&2; exit 1; }
done
mkdir -p "$ROOT_ARG"
ROOT="$(cd "$ROOT_ARG" && pwd -P)"
SANDBOX="$ROOT/x-rag-sandbox"

TMP=$(mktemp -d)
trap 'rm -rf "$TMP"' EXIT

# git sans signature ni identité personnelle : l'auteur des MRs reconstituées = partie locale
# de l'email (« testbed »), quelle que soit la config globale du poste. push.negotiate
# désactivé : inutile vers un bare local et bruyant sur un premier push (bare vide).
g() { git -c commit.gpgsign=false -c user.name=testbed -c user.email=testbed@example.com -c push.negotiate=false "$@"; }
# dated <date ISO> git-args... : date d'auteur ET de committer imposées (ancienneté des MRs)
dated() { local when=$1; shift; GIT_AUTHOR_DATE="$when" GIT_COMMITTER_DATE="$when" g "$@"; }

bare_of() { echo "$SANDBOX/$1.git"; }

work_clone() { # work_clone <project> — clone de travail temporaire du miroir ; affiche son chemin
  local dir="$TMP/$1"
  g clone -q "$(bare_of "$1")" "$dir"
  echo "$dir"
}

create_mirror() { # create_mirror <project> — bare vide, configuré comme un « clone --mirror »
  local bare; bare=$(bare_of "$1")
  g init -q --bare --initial-branch=main "$bare"
  g -C "$bare" config remote.origin.url "$SANDBOX_REMOTE_BASE/$1.git"
  g -C "$bare" config remote.origin.fetch '+refs/*:refs/*'
  g -C "$bare" config remote.origin.mirror true
  g -C "$bare" config gc.autoDetach false            # comme scripts/git-mirror-init.sh
}

push_dir() { # push_dir <project> — pousse testbed/projects/<project> comme main (état initial)
  local work="$TMP/$1"
  mkdir -p "$work"
  cp -r "$TESTBED_DIR/projects/$1"/. "$work/"
  g -C "$work" init -q -b main
  g -C "$work" add -A
  dated "$D_INIT" -C "$work" commit -qm "chore: état initial du testbed"
  g -C "$work" remote add origin "$(bare_of "$1")"
  g -C "$work" push -q origin main
}

branch_commit() { # branch_commit <work> <branch> <date> <repo-path> <content> <subject> <body>
  local work=$1 branch=$2 when=$3 path=$4 content=$5 subject=$6 body=$7
  g -C "$work" checkout -q -b "$branch" main
  mkdir -p "$work/$(dirname "$path")"
  printf '%s\n' "$content" > "$work/$path"
  g -C "$work" add -A
  # sujet = titre de la MR, corps = sa description : ce que GitLab pré-remplit depuis l'unique
  # commit d'une branche — et ce que le connecteur reprend comme titre d'une MR ouverte
  dated "$when" -C "$work" commit -q -m "$subject" -m "$body"
  g -C "$work" push -q origin "$branch"
  g -C "$work" checkout -q main
}

gitlab_merge() { # gitlab_merge <work> <branch> <title> <project> <iid> <date> — fusion --no-ff, message GitLab par défaut
  local work=$1 branch=$2 title=$3 project=$4 iid=$5 when=$6
  # Template GitLab par défaut : « Merge branch 'src' into 'dst' » / titre / « See merge request grp/proj!n »
  dated "$when" -C "$work" merge -q --no-ff --no-edit \
    -m "Merge branch '$branch' into 'main'" -m "$title" \
    -m "See merge request passerelle/x-rag-sandbox/$project!$iid" "$branch"
  g -C "$work" push -q origin main
}

case "$ACTION" in
  init)
    if [ -e "$SANDBOX" ]; then
      echo "$SANDBOX existe déjà — supprimer ce dossier pour repartir de zéro (rm -rf \"$SANDBOX\")" >&2
      exit 1
    fi
    mkdir -p "$SANDBOX"
    for p in "${PROJECTS[@]}"; do
      echo "Miroir $p.git..."
      create_mirror "$p"
      push_dir "$p"
    done

    echo "MRs (dates de commit croissantes — MR-1 = la plus vieille)..."
    # MR-1 (ouverte, avec clé Jira) : réponse attendue de « la MR ouverte la plus vieille ».
    # Branche non fusionnée -> id git-branch:fake-orders:feat/xragsand-1-suivi-commande
    branch_commit "$TMP/fake-orders" feat/xragsand-1-suivi-commande "$D_MR1" \
      "src/main/java/com/sandbox/orders/OrderTracking.java" \
      '// XRAGSAND-1 : suivi de commande (en cours)' \
      "XRAGSAND-1 Suivi de commande" "Implémente le suivi demandé par XRAGSAND-1."
    echo "  MR-1 : branche ouverte feat/xragsand-1-suivi-commande (fake-orders)"

    # MR-2 (ouverte, SANS clé Jira — piège REFERENCES) -> id git-branch:fake-billing:feat/refonte-tva
    branch_commit "$TMP/fake-billing" feat/refonte-tva "$D_MR2" \
      "src/main/java/com/sandbox/billing/VatCalculator.java" \
      '// refonte du calcul de TVA (en cours)' \
      "Refonte du calcul de TVA" "Sans référence Jira — volontaire (piège du scénario)."
    echo "  MR-2 : branche ouverte feat/refonte-tva (fake-billing)"

    # MR-3 (mergée, avec clé Jira) : commit de fusion au format GitLab -> id git:fake-billing:3, iid 3.
    # La branche source reste poussée (GitLab ne la supprime pas par défaut) : fusionnée dans
    # main, elle ne doit PAS ressortir comme MR ouverte.
    branch_commit "$TMP/fake-billing" feat/xragsand-3-export-csv "$D_MR3" \
      "src/main/java/com/sandbox/billing/InvoiceCsvExporter.java" \
      '// XRAGSAND-3 : export CSV des factures' \
      "XRAGSAND-3 export CSV des factures" "Ferme XRAGSAND-3."
    gitlab_merge "$TMP/fake-billing" feat/xragsand-3-export-csv \
      "XRAGSAND-3 Export CSV des factures" fake-billing 3 "$D_MR3_MERGE"
    echo "  MR-3 : fusionnée dans main de fake-billing (See merge request passerelle/x-rag-sandbox/fake-billing!3)"
    echo "Sandbox prêt : $SANDBOX — .env : GIT_REPOS_DIR=$ROOT (team-config : bloc sources.git de team-config.testbed.yml)"
    ;;

  increment)
    # Étape B du scénario : nouvelle arête CALLS_API fake-orders -> fake-billing (commit direct sur main)
    work=$(work_clone fake-orders)
    cp "$TESTBED_DIR/increments/PaymentClient.java" "$work/src/main/java/com/sandbox/orders/PaymentClient.java"
    g -C "$work" add -A
    g -C "$work" commit -qm "feat: encaissement via fake-billing (nouvelle relation CALLS_API)"
    g -C "$work" push -q origin main
    echo "PaymentClient.java poussé sur fake-orders/main — pas de webhook en mode git : POST /api/admin/sync?source=gitlab-code (ou attendre le nightly), puis vérifier l'arête."
    ;;

  prune)
    # Étape C du scénario : suppression -> purge des chunks/nœuds orphelins au nightly
    work=$(work_clone fake-billing)
    g -C "$work" rm -q src/main/java/com/sandbox/billing/LegacyOrdersWebClient.java
    g -C "$work" commit -qm "chore: suppression du client legacy"
    g -C "$work" push -q origin main
    echo "LegacyOrdersWebClient.java supprimé — vérifier la purge après le prochain nightly (scenario.md §4C)."
    ;;

  merge-mr1)
    # Étape D du scénario : cycle de vie MR. Fusion de MR-1 au format GitLab (iid 1 : première MR
    # de fake-orders) + suppression de la branche source -> à la sync gitlab-mr suivante, la MR
    # ouverte git-branch:… est retirée (réconciliation) et git:fake-orders:1 apparaît en merged.
    work=$(work_clone fake-orders)
    g -C "$work" checkout -q -b feat/xragsand-1-suivi-commande origin/feat/xragsand-1-suivi-commande
    g -C "$work" checkout -q main
    gitlab_merge "$work" feat/xragsand-1-suivi-commande "XRAGSAND-1 Suivi de commande" fake-orders 1 "$(date --iso-8601=seconds)"
    g -C "$work" push -q origin --delete feat/xragsand-1-suivi-commande
    echo "MR-1 fusionnée dans fake-orders/main, branche supprimée — POST /api/admin/sync?source=gitlab-mr puis rejouer Q2 (scenario.md §4D)."
    ;;

  *)
    usage
    ;;
esac
