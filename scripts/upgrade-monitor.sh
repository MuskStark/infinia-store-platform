#!/usr/bin/env bash
# Deploy an exact revision on the independent monitor host, without waiting
# for another CI system to publish a mutable :latest image.
set -euo pipefail
REF=origin/main
DEPLOY_PATH=.
while [[ $# -gt 0 ]]; do
  case "$1" in
    --ref) REF=${2:?}; shift 2 ;;
    --path) DEPLOY_PATH=${2:?}; shift 2 ;;
    *) echo "Unknown argument: $1" >&2; exit 2 ;;
  esac
done
[[ $EUID -eq 0 ]] || { echo 'Run with sudo -n bash' >&2; exit 1; }
cd "$DEPLOY_PATH"
exec 9>.git/monitor-deploy.lock
flock -n 9 || { echo 'Another monitor deployment is active' >&2; exit 1; }
[[ -f .env ]] || { echo 'Existing monitor .env is required' >&2; exit 1; }
BASE=docker-compose.monitor.yml
OVERRIDE=.monitor-release.yml
OLD_REF=$(git rev-parse HEAD)
OLD_CID=$(docker compose -f "$BASE" ps -q monitor)
OLD_IMAGE=
if [[ -n $OLD_CID ]]; then
  OLD_IMAGE=$(docker inspect -f '{{.Image}}' "$OLD_CID")
fi
BACKUP=$(mktemp)
HAD_OVERRIDE=0
if [[ -f $OVERRIDE ]]; then cp "$OVERRIDE" "$BACKUP"; HAD_OVERRIDE=1; fi
trap 'rm -f "$BACKUP"' EXIT
rollback() {
  trap - ERR
  echo "Monitor deploy failed; restoring $OLD_REF" >&2
  git checkout --detach "$OLD_REF"
  if [[ $HAD_OVERRIDE -eq 1 ]]; then
    cp "$BACKUP" "$OVERRIDE"
  elif [[ -n $OLD_IMAGE ]]; then
    printf 'services:\n  monitor:\n    image: "%s"\n' "$OLD_IMAGE" > "$OVERRIDE"
  fi
  if [[ -n $OLD_IMAGE ]]; then
    docker compose -f "$BASE" -f "$OVERRIDE" up -d --no-build --pull never --wait --wait-timeout 300 || true
  fi
  exit 1
}
git fetch origin --prune
NEW_REF=$(git rev-parse --verify "${REF}^{commit}")
trap rollback ERR
git checkout --detach "$NEW_REF"
IMAGE="infinia-monitor:$NEW_REF"
docker build -f Dockerfile.monitor --label "org.opencontainers.image.revision=$NEW_REF" -t "$IMAGE" .
printf 'services:\n  monitor:\n    image: "%s"\n' "$IMAGE" > "$OVERRIDE"
docker compose -f "$BASE" -f "$OVERRIDE" up -d --no-build --pull never --wait --wait-timeout 300
CID=$(docker compose -f "$BASE" -f "$OVERRIDE" ps -q monitor)
[[ $(docker inspect -f '{{.State.Health.Status}}' "$CID") == healthy ]]
[[ $(docker inspect -f '{{index .Config.Labels "org.opencontainers.image.revision"}}' "$CID") == "$NEW_REF" ]]
trap - ERR
printf 'Monitor deployed and healthy: %s\n' "$NEW_REF"
