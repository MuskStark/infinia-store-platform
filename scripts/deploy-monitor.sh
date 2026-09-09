#!/usr/bin/env bash
#
# One-click deployment of the standalone status monitor (the split
# deployment's monitor host, DEPLOYMENT.md "Monitor host"): installs Docker
# where missing, generates .env, pulls the CI-published image (or builds
# locally with --build), waits for health and verifies the store is actually
# reachable from this host.
#
# Usage (root or sudo, from the repo root):
#   sudo scripts/deploy-monitor.sh --target-url https://store.example.com
#
# Options:
#   --target-url URL      the store's URL as THIS host reaches it (required;
#                          through the WAF's public https URL preferred)
#   --alert-webhook URL   MONITOR_ALERT_WEBHOOK (optional)
#   --image-registry HOST image registry prefix (default ghcr.io; use a GHCR
#                          proxy such as ghcr.m.daocloud.io when direct
#                          ghcr.io pulls are slow/unreachable)
#   --image-tag TAG       image tag (default latest; pin a digest for
#                          immutability)
#   --build               build the image locally (Dockerfile.monitor) instead
#                          of pulling — slow, only for air-gapped networks
#   --registry-mirror URL docker hub registry mirror; repeatable
#                          (default: docker.m.daocloud.io docker.1ms.run)
#   --no-mirror           leave /etc/docker/daemon.json untouched
#   --apt-mirror HOST     apt source host for Debian/Ubuntu
#                          (default mirrors.aliyun.com; --no-apt-mirror skips)
#   --skip-docker         assume Docker + compose are already installed
#   --force-env           regenerate .env even if one exists
#   --no-up               prepare everything but do not pull/start
#   --yes                 non-interactive: never prompt, accept defaults
#   -h, --help            this help
#
# Idempotent: an existing .env and any existing registry-mirrors config are
# preserved; apt sources are backed up to /etc/apt/deploy-backup-<ts>.tgz.
# The monitor's history lives in the monitordata volume and survives re-runs.
set -euo pipefail
cd "$(dirname "$0")/.."

usage() { sed -n '2,36p' "$0" | sed 's/^# \{0,1\}//'; }

log()  { printf '\033[1;32m==>\033[0m %s\n' "$*"; }
warn() { printf '\033[1;33mWARNING:\033[0m %s\n' "$*"; }
die()  { printf '\033[1;31mERROR:\033[0m %s\n' "$*" >&2; exit 1; }

# ---------------------------------------------------------------- flags -----
TARGET_URL=""
ALERT_WEBHOOK=""
IMAGE_REGISTRY="ghcr.io"
IMAGE_TAG="latest"
BUILD_LOCAL=0
REGISTRY_MIRRORS=()
NO_MIRROR=0
APT_MIRROR="mirrors.aliyun.com"
SKIP_DOCKER=0
FORCE_ENV=0
NO_UP=0
ASSUME_YES=0

while [[ $# -gt 0 ]]; do
  case "$1" in
    --target-url)      TARGET_URL=${2:?}; shift 2 ;;
    --alert-webhook)   ALERT_WEBHOOK=${2:?}; shift 2 ;;
    --image-registry)  IMAGE_REGISTRY=${2:?}; shift 2 ;;
    --image-tag)       IMAGE_TAG=${2:?}; shift 2 ;;
    --build)           BUILD_LOCAL=1; shift ;;
    --registry-mirror) REGISTRY_MIRRORS+=("${2:?}"); shift 2 ;;
    --no-mirror)       NO_MIRROR=1; shift ;;
    --apt-mirror)      APT_MIRROR=${2:?}; shift 2 ;;
    --no-apt-mirror)   APT_MIRROR=""; shift ;;
    --skip-docker)     SKIP_DOCKER=1; shift ;;
    --force-env)       FORCE_ENV=1; shift ;;
    --no-up)           NO_UP=1; shift ;;
    --yes|-y)          ASSUME_YES=1; shift ;;
    -h|--help)         usage; exit 0 ;;
    *)                 usage >&2; die "unknown option: $1" ;;
  esac
done
if [[ ${#REGISTRY_MIRRORS[@]} -eq 0 && $NO_MIRROR -eq 0 ]]; then
  REGISTRY_MIRRORS=("https://docker.m.daocloud.io" "https://docker.1ms.run")
fi
COMPOSE_FILE="docker-compose.monitor.yml"
[[ -f $COMPOSE_FILE ]] || die "$COMPOSE_FILE not found — run from the repo root"

# ---------------------------------------------------------- host checks -----
[[ $(uname -s) == "Linux" ]] || die "this deployer targets Linux production hosts"
if [[ ${EUID} -ne 0 ]]; then
  command -v sudo >/dev/null 2>&1 || die "must run as root (try: sudo scripts/deploy-monitor.sh)"
  log "not root — re-running with sudo"
  exec sudo bash "$0" "$@"
fi

# -------------------------------------------------------- distro detect -----
if [[ -f /etc/os-release ]]; then
  # shellcheck disable=SC1091
  . /etc/os-release
else
  die "/etc/os-release missing — unsupported distro"
fi
PKG=""
if command -v apt-get >/dev/null 2>&1; then
  PKG=apt
elif command -v dnf >/dev/null 2>&1; then
  PKG=dnf
elif command -v yum >/dev/null 2>&1; then
  PKG=yum
fi

pkg_install() {
  if [[ $PKG == apt ]]; then
    DEBIAN_FRONTEND=noninteractive apt-get install -y "$@"
  elif [[ $PKG == dnf ]]; then
    dnf install -y "$@"
  elif [[ $PKG == yum ]]; then
    yum install -y "$@"
  else
    die "no supported package manager (apt/dnf/yum) — install manually: $*"
  fi
}

# ------------------------------------------------- 1. apt source mirror -----
if [[ -n $APT_MIRROR && $PKG == apt ]]; then
  if grep -rqF "$APT_MIRROR" /etc/apt/sources.list /etc/apt/sources.list.d 2>/dev/null; then
    log "apt sources already point at $APT_MIRROR — skipping rewrite"
  else
    STAMP=$(date +%Y%m%dT%H%M%S)
    BACKUP="/etc/apt/deploy-backup-$STAMP.tgz"
    tar -czf "$BACKUP" /etc/apt/sources.list /etc/apt/sources.list.d 2>/dev/null || true
    log "apt sources backed up to $BACKUP"
    find -H /etc/apt -maxdepth 2 \
      \( -name '*.list' -o -name '*.sources' \) -type f -exec \
      sed -i -E "s#(https?://)(archive\.ubuntu\.com|cn\.archive\.ubuntu\.com|security\.ubuntu\.com|deb\.debian\.org|security\.debian\.org|mirrors\.cloud\.aliyuncs\.com)/#\1${APT_MIRROR}/#g" {} +
    log "apt sources rewritten to $APT_MIRROR"
    apt-get update
  fi
fi

# ---------------------------------------------------- 2. install docker -----
install_docker() {
  log "installing Docker Engine + compose plugin"
  if [[ $PKG == apt && -n ${VERSION_CODENAME:-} ]]; then
    pkg_install ca-certificates curl openssl
    install -m 0755 -d /etc/apt/keyrings
    curl -fsSL "https://mirrors.aliyun.com/docker-ce/linux/${ID}/gpg" -o /etc/apt/keyrings/docker.asc
    chmod a+r /etc/apt/keyrings/docker.asc
    echo "deb [arch=$(dpkg --print-architecture) signed-by=/etc/apt/keyrings/docker.asc] https://mirrors.aliyun.com/docker-ce/linux/${ID} ${VERSION_CODENAME} stable" \
      > /etc/apt/sources.list.d/docker.list
    apt-get update
    pkg_install docker-ce docker-ce-cli containerd.io docker-buildx-plugin docker-compose-plugin
  elif [[ $PKG == dnf || $PKG == yum ]]; then
    repo_distro=centos; if [[ ${ID:-} == fedora ]]; then repo_distro=fedora; fi
    if [[ $PKG == dnf ]]; then
      dnf -y config-manager --add-repo "https://mirrors.aliyun.com/docker-ce/linux/${repo_distro}/docker-ce.repo"
    else
      pkg_install yum-utils
      yum-config-manager --add-repo "https://mirrors.aliyun.com/docker-ce/linux/${repo_distro}/docker-ce.repo"
    fi
    pkg_install docker-ce docker-ce-cli containerd.io docker-buildx-plugin docker-compose-plugin
  else
    log "no apt/dnf/yum repo path — falling back to get.docker.com --mirror Aliyun"
    pkg_install curl openssl || true
    curl -fsSL https://get.docker.com -o /tmp/get-docker.sh
    sh /tmp/get-docker.sh --mirror Aliyun
  fi
  systemctl enable --now docker 2>/dev/null || service docker start 2>/dev/null || true
}

if [[ $SKIP_DOCKER -eq 1 ]]; then
  log "--skip-docker: trusting the host's Docker install"
else
  if command -v docker >/dev/null 2>&1 && docker info >/dev/null 2>&1; then
    log "Docker already working — skipping install"
  else
    install_docker
  fi
fi
command -v docker >/dev/null 2>&1 || die "docker not installed"
docker info >/dev/null 2>&1 || die "docker daemon not reachable (systemctl status docker?)"
docker compose version >/dev/null 2>&1 || die "docker compose plugin missing — install docker-compose-plugin"

# ---------------------------------------------- 3. registry mirrors ---------
# Only Docker Hub pulls consult these; the monitor image comes from GHCR and
# uses --image-registry above. Kept for parity with deploy.sh.
if [[ $NO_MIRROR -eq 1 ]]; then
  log "--no-mirror: leaving /etc/docker/daemon.json untouched"
else
  CFG=/etc/docker/daemon.json
  mkdir -p /etc/docker
  MIRRORS_JSON=$(printf '%s\n' "${REGISTRY_MIRRORS[@]}" | sed 's/^/"/;s/$/"/' | paste -sd, - | sed 's/^/[/;s/$/]/')
  if [[ -f $CFG ]] && grep -q 'registry-mirrors' "$CFG"; then
    log "daemon.json already defines registry-mirrors — keeping them"
  elif [[ ! -f $CFG ]]; then
    printf '{\n  "registry-mirrors": %s\n}\n' "$MIRRORS_JSON" > "$CFG"
    log "registry mirrors written to $CFG"
    systemctl restart docker 2>/dev/null || service docker restart
  elif command -v python3 >/dev/null 2>&1; then
    cp -a "$CFG" "${CFG}.bak-$(date +%Y%m%dT%H%M%S)"
    MIRRORS_JSON=$MIRRORS_JSON python3 - <<'PY'
import json, os
path = "/etc/docker/daemon.json"
with open(path) as f:
    cfg = json.load(f)
cfg["registry-mirrors"] = json.loads(os.environ["MIRRORS_JSON"])
with open(path, "w") as f:
    json.dump(cfg, f, indent=2)
    f.write("\n")
PY
    log "registry mirrors merged into $CFG (backup kept alongside)"
    systemctl restart docker 2>/dev/null || service docker restart
  else
    warn "daemon.json exists but no python3 to merge — add manually: \"registry-mirrors\": $MIRRORS_JSON"
  fi
fi

# ---------------------------------------------------- 4. generate .env -----
command -v openssl >/dev/null 2>&1 || pkg_install openssl

if [[ -f .env && $FORCE_ENV -eq 0 ]]; then
  log ".env already exists — keeping it (use --force-env to regenerate)"
  TARGET_URL=$(grep -E '^MONITOR_TARGET_BASE_URL=' .env | cut -d= -f2- || true)
else
  if [[ -z $TARGET_URL ]]; then
    if [[ $ASSUME_YES -eq 0 && -t 0 ]]; then
      read -r -e -p "Store URL as this host reaches it (MONITOR_TARGET_BASE_URL, e.g. https://store.example.com): " TARGET_URL
    fi
  fi
  [[ -n $TARGET_URL ]] || die "--target-url is required (the store's URL as this host reaches it — through the WAF's public https URL preferred)"
  {
    printf '# Generated by scripts/deploy-monitor.sh on %s.\n' "$(date -u +%FT%TZ)"
    printf 'MONITOR_TARGET_BASE_URL=%s\n' "$TARGET_URL"
    if [[ -n $ALERT_WEBHOOK ]]; then
      printf 'MONITOR_ALERT_WEBHOOK=%s\n' "$ALERT_WEBHOOK"
    else
      printf '#MONITOR_ALERT_WEBHOOK=\n'
    fi
    if [[ $IMAGE_REGISTRY != "ghcr.io" ]]; then
      printf 'MONITOR_IMAGE_REGISTRY=%s\n' "$IMAGE_REGISTRY"
    fi
    if [[ $IMAGE_TAG != "latest" ]]; then
      printf 'MONITOR_IMAGE_TAG=%s\n' "$IMAGE_TAG"
    fi
  } > .env
  chmod 600 .env
  log ".env generated: MONITOR_TARGET_BASE_URL=$TARGET_URL registry=$IMAGE_REGISTRY tag=$IMAGE_TAG"
fi
[[ -n $TARGET_URL ]] || TARGET_URL=$(grep -E '^MONITOR_TARGET_BASE_URL=' .env | cut -d= -f2-)
[[ -n $TARGET_URL ]] || die ".env has no MONITOR_TARGET_BASE_URL — rerun with --target-url or --force-env"

# ------------------------------------------------- 5. pull & start --------
if [[ $NO_UP -eq 1 ]]; then
  log "--no-up: environment prepared; start later with: docker compose -f $COMPOSE_FILE up -d"
  exit 0
fi

if [[ $BUILD_LOCAL -eq 1 ]]; then
  log "--build: building the monitor image locally (first build pulls Maven/npm deps — be patient)"
  # The shipped file is pull-only; the override adds the local build section.
  cat > docker-compose.monitor-build.override.yml <<'OVERRIDE'
services:
  monitor:
    build:
      context: .
      dockerfile: Dockerfile.monitor
OVERRIDE
  docker compose -f "$COMPOSE_FILE" -f docker-compose.monitor-build.override.yml up -d --build
else
  log "pulling the monitor image ($IMAGE_REGISTRY/muskstark/infinia-store-platform-monitor:$IMAGE_TAG)"
  if ! docker compose -f "$COMPOSE_FILE" pull; then
    die "image pull failed — if ghcr.io is unreachable from this host, retry with --image-registry ghcr.m.daocloud.io, or --build to compile locally"
  fi
  docker compose -f "$COMPOSE_FILE" up -d
fi

wait_healthy() { # wait_healthy <service> <timeout-seconds>
  local svc=$1 timeout=$2 waited=0 cid st
  cid=$(docker compose -f "$COMPOSE_FILE" -f docker-compose.monitor-build.override.yml ps -q "$svc" 2>/dev/null)
  cid=${cid:-$(docker compose -f "$COMPOSE_FILE" ps -q "$svc")}
  if [[ -z $cid ]]; then
    docker compose -f "$COMPOSE_FILE" logs --tail 50 "$svc" || true
    die "container '$svc' is not running"
  fi
  while :; do
    st=$(docker inspect -f '{{if .State.Health}}{{.State.Health.Status}}{{else}}none{{end}}' "$cid" 2>/dev/null || echo starting)
    if [[ $st == healthy ]]; then
      log "$svc is healthy"
      return 0
    fi
    if [[ $st == unhealthy && $waited -ge 90 ]]; then
      docker compose -f "$COMPOSE_FILE" logs --tail 50 "$svc" || true
      die "$svc reports unhealthy"
    fi
    if [[ $waited -ge $timeout ]]; then
      docker compose -f "$COMPOSE_FILE" logs --tail 50 "$svc" || true
      die "$svc did not become healthy within ${timeout}s"
    fi
    waited=$((waited + 5))
    sleep 5
  done
}

wait_healthy monitor 300

log "verifying the monitor endpoint"
curl -fsS http://127.0.0.1:8090/actuator/health >/dev/null || warn "monitor health endpoint not reachable on 127.0.0.1:8090"

# The #1 misconfiguration: the monitor cannot reach the store at all
# (firewall / security group / wrong URL) — probe it once from this host.
log "probing the store from this host: $TARGET_URL"
if ! curl -fsS --max-time 10 "$TARGET_URL/actuator/health" 2>/dev/null | grep -q UP; then
  warn "the store did not answer at $TARGET_URL — the monitor will report it DOWN. Check: URL spelling, store-host firewall (DOCKER-USER), cloud security group, and WAF CC rules against this host's polling."
else
  log "store reachable from this host"
fi

# ---------------------------------------------------- 6. what's next -------
cat <<SUMMARY

Monitor is up. Remaining wiring (see DEPLOYMENT.md "Monitor host"):

  1. Reverse proxy / WAF — publish the status page (anonymous by design):
       monitor  -> http://$(hostname -I 2>/dev/null | awk '{print $1}'):8090
     Health-check path: GET /actuator/health. If MONITOR_TARGET_BASE_URL goes
     through the WAF, whitelist this host's IP for CC protection so the
     once-a-minute polling is not blocked.
  2. First status (after ~1 poll cycle):
       curl http://127.0.0.1:8090/api/v1/status     # stale must be false
  3. Useful commands:
       docker compose -f $COMPOSE_FILE ps
       docker compose -f $COMPOSE_FILE logs -f monitor
       docker compose -f $COMPOSE_FILE pull && docker compose -f $COMPOSE_FILE up -d   # upgrade

SUMMARY
