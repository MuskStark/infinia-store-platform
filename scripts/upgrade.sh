#!/usr/bin/env bash
#
# Unattended upgrade of an existing store deployment (DEPLOYMENT.md
# "Upgrades" / "Automated deploys"). CI runs this over SSH after a green
# build (.github/workflows/ci.yml, deploy job); by hand it works the same:
#
#   sudo scripts/upgrade.sh                      # deploy origin/main
#   sudo scripts/upgrade.sh --ref <sha|tag>      # deploy an exact commit
#
# Steps: pin the checkout to the target commit (tracked files only — the
# untracked .env is never touched, local edits to tracked files are
# discarded), rebuild and restart via compose, wait for the health checks;
# when anything fails, restore the previous image and checkout (rollback)
# and exit non-zero so CI goes red.
#
# Requirements: the repo cloned on this host with credentials for an
# unattended `git fetch origin` (GitHub deploy key or credential helper)
# and a working Docker Engine + compose plugin.
set -euo pipefail
cd "$(dirname "$0")/.."

usage() { sed -n '2,18p' "$0" | sed 's/^# \{0,1\}//'; }

log()  { printf '\033[1;32m==>\033[0m %s\n' "$*"; }
warn() { printf '\033[1;33mWARNING:\033[0m %s\n' "$*"; }
die()  { printf '\033[1;31mERROR:\033[0m %s\n' "$*" >&2; exit 1; }

# ---------------------------------------------------------------- flags -----
REF="origin/main"
while [[ $# -gt 0 ]]; do
  case "$1" in
    --ref)     REF=${2:?}; shift 2 ;;
    -h|--help) usage; exit 0 ;;
    *)         usage >&2; die "unknown option: $1" ;;
  esac
done

# ---------------------------------------------------------- host checks -----
[[ $(uname -s) == "Linux" ]] || die "this upgrader targets Linux production hosts"
if [[ ${EUID} -ne 0 ]]; then
  # Never prompt: unattended callers (CI, cron) must fail fast instead.
  command -v sudo >/dev/null 2>&1 || die "must run as root (try: sudo scripts/upgrade.sh)"
  exec sudo -n bash "$0" "$@"
fi
[[ -f docker-compose.yml ]] || die "run from the repo checkout (docker-compose.yml not found next to scripts/)"
command -v git >/dev/null 2>&1 || die "git not installed"
docker info >/dev/null 2>&1 || die "docker daemon not reachable"
docker compose version >/dev/null 2>&1 || die "docker compose plugin missing"

# ------------------------------------------------------------ fetch ---------
OLD_REF=$(git rev-parse HEAD)
log "fetching origin (current checkout: ${OLD_REF:0:12})"
git fetch origin --prune
NEW_REF=$(git rev-parse --verify "${REF}^{commit}") || die "unknown ref: $REF"

# ------------------------------------------ profiles & rollback snapshot ----
# The `app` profile always; the co-located monitor (single-host install)
# rides along when its container exists, so split deployments are unaffected.
PROFILES=(--profile app)
WITH_MONITOR=0
if [[ -n "$(docker compose ps -q monitor 2>/dev/null || true)" ]]; then
  PROFILES+=(--profile monitor)
  WITH_MONITOR=1
fi
log "compose profiles: ${PROFILES[*]}"

# The rebuild below retags :latest, so snapshot the currently running image
# first; a failed deploy then rolls back with one retag + compose up.
if docker image inspect infinia-store:latest >/dev/null 2>&1; then
  docker tag infinia-store:latest infinia-store:rollback
else
  warn "no existing infinia-store image — nothing to roll back to on failure"
fi
if [[ $WITH_MONITOR -eq 1 ]] && docker image inspect infinia-monitor:latest >/dev/null 2>&1; then
  docker tag infinia-monitor:latest infinia-monitor:rollback
fi

rollback() {
  warn "deploy failed — restoring the previous version (${OLD_REF:0:12})"
  git checkout -f --detach "$OLD_REF" >/dev/null 2>&1 || true
  if docker image inspect infinia-store:rollback >/dev/null 2>&1; then
    docker tag infinia-store:rollback infinia-store:latest
  fi
  if [[ $WITH_MONITOR -eq 1 ]] && docker image inspect infinia-monitor:rollback >/dev/null 2>&1; then
    docker tag infinia-monitor:rollback infinia-monitor:latest
  fi
  docker compose "${PROFILES[@]}" up -d --no-build \
    || warn "rollback 'up -d' reported an error — check: docker compose ps"
  die "rolled back to ${OLD_REF:0:12} (investigate: docker compose logs --tail 100 store)"
}

wait_healthy() { # wait_healthy <service> <timeout-seconds>; 0 = healthy, 1 = not
  local svc=$1 timeout=$2 waited=0 cid st
  cid=$(docker compose ps -q "$svc")
  if [[ -z $cid ]]; then
    warn "container '$svc' is not running"
    return 1
  fi
  while :; do
    st=$(docker inspect -f '{{if .State.Health}}{{.State.Health.Status}}{{else}}none{{end}}' "$cid" 2>/dev/null || echo starting)
    if [[ $st == healthy ]]; then
      log "$svc is healthy"
      return 0
    fi
    if [[ $st == unhealthy && $waited -ge 120 ]]; then
      docker compose logs --tail 50 "$svc" || true
      return 1
    fi
    if [[ $waited -ge $timeout ]]; then
      docker compose logs --tail 50 "$svc" || true
      return 1
    fi
    waited=$((waited + 5))
    sleep 5
  done
}

# ------------------------------------------------------ checkout & build ----
log "checkout ${OLD_REF:0:12} -> ${NEW_REF:0:12}"
git checkout -f --detach "$NEW_REF" >/dev/null

log "rebuilding and restarting (compose layer cache keeps unchanged builds fast)"
docker compose "${PROFILES[@]}" up -d --build || rollback

if ! wait_healthy store 600; then rollback; fi
if [[ $WITH_MONITOR -eq 1 ]]; then
  if ! wait_healthy monitor 300; then rollback; fi
fi

# Loopback sanity probe, warn-only (same as deploy.sh — a rebinding
# STORE_BIND_HOST can make 127.0.0.1 the wrong address to test).
curl -fsS http://127.0.0.1:8080/actuator/health | grep -q '"UP"' \
  || warn "store health endpoint did not report UP"
if [[ $WITH_MONITOR -eq 1 ]]; then
  curl -fsS http://127.0.0.1:8090/actuator/health >/dev/null \
    || warn "monitor health endpoint not reachable"
fi

# Drop the dangling layers the rebuild left behind so repeated deploys don't
# creep the disk (tagged images — including :rollback — are kept).
docker image prune -f >/dev/null

log "deployed ${NEW_REF:0:12} (was ${OLD_REF:0:12}); rollback image kept as infinia-store:rollback"
