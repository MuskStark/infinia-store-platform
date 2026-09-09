#!/usr/bin/env bash
#
# One-click production deployment for the Infinia Store Platform on a fresh
# Linux host (DEPLOYMENT.md, automated):
#
#   1. (optional) rewrites Debian/Ubuntu apt sources to a domestic mirror,
#   2. installs Docker Engine + buildx + compose plugin (Aliyun docker-ce repo,
#      get.docker.com --mirror Aliyun as fallback),
#   3. configures docker registry mirrors in /etc/docker/daemon.json,
#   4. generates .env with fresh secrets (openssl rand -hex 32),
#   5. builds and starts the `app` profile and waits for health,
#   6. prints the reverse-proxy / WAF (SafeLine) wiring checklist.
#
# Usage (root or sudo):
#   sudo scripts/deploy.sh --base-url https://store.example.com
#
# Options:
#   --base-url URL         public https URL of the store (STORE_BASE_URL)
#   --monitor-url URL      URL the monitor probes (default: --base-url)
#   --alert-webhook URL    MONITOR_ALERT_WEBHOOK (optional)
#   --registry-mirror URL  docker registry mirror; repeatable
#                           (default: docker.m.daocloud.io docker.1ms.run)
#   --no-mirror            leave /etc/docker/daemon.json untouched
#   --apt-mirror HOST      apt source host for Debian/Ubuntu
#                           (default: mirrors.aliyun.com; --no-apt-mirror skips)
#   --skip-docker          assume Docker + compose are already installed
#   --force-env            regenerate .env even if one exists (re-rolls secrets!)
#   --no-up                prepare everything but do not build/start the stack
#   --with-monitor         also run the status monitor on this host (single-host
#                          installs; the split deployment runs it on its own
#                          host via docker-compose.monitor.yml instead)
#   --yes                  non-interactive: never prompt, accept defaults
#   -h, --help             this help
#
# Idempotent: an existing .env and any existing registry-mirrors config are
# preserved; apt sources are backed up to /etc/apt/deploy-backup-<ts>.tgz.
# The repo must already be on the host (git clone / rsync); first build pulls
# base images plus Maven/npm dependencies and can take a while.
set -euo pipefail
cd "$(dirname "$0")/.."

usage() { sed -n '2,35p' "$0" | sed 's/^# \{0,1\}//'; }

log()  { printf '\033[1;32m==>\033[0m %s\n' "$*"; }
warn() { printf '\033[1;33mWARNING:\033[0m %s\n' "$*"; }
die()  { printf '\033[1;31mERROR:\033[0m %s\n' "$*" >&2; exit 1; }

# ---------------------------------------------------------------- flags -----
BASE_URL=""
MONITOR_URL=""
ALERT_WEBHOOK=""
REGISTRY_MIRRORS=()
NO_MIRROR=0
APT_MIRROR="mirrors.aliyun.com"
SKIP_DOCKER=0
FORCE_ENV=0
NO_UP=0
WITH_MONITOR=0
ASSUME_YES=0

while [[ $# -gt 0 ]]; do
  case "$1" in
    --base-url)        BASE_URL=${2:?}; shift 2 ;;
    --monitor-url)     MONITOR_URL=${2:?}; shift 2 ;;
    --alert-webhook)   ALERT_WEBHOOK=${2:?}; shift 2 ;;
    --registry-mirror) REGISTRY_MIRRORS+=("${2:?}"); shift 2 ;;
    --no-mirror)       NO_MIRROR=1; shift ;;
    --apt-mirror)      APT_MIRROR=${2:?}; shift 2 ;;
    --no-apt-mirror)   APT_MIRROR=""; shift ;;
    --skip-docker)     SKIP_DOCKER=1; shift ;;
    --force-env)       FORCE_ENV=1; shift ;;
    --no-up)           NO_UP=1; shift ;;
    --with-monitor)    WITH_MONITOR=1; shift ;;
    --yes|-y)          ASSUME_YES=1; shift ;;
    -h|--help)         usage; exit 0 ;;
    *)                 usage >&2; die "unknown option: $1" ;;
  esac
done
if [[ ${#REGISTRY_MIRRORS[@]} -eq 0 && $NO_MIRROR -eq 0 ]]; then
  # Public mirrors change availability often; override with --registry-mirror.
  REGISTRY_MIRRORS=("https://docker.m.daocloud.io" "https://docker.1ms.run")
fi

# ---------------------------------------------------------- host checks -----
[[ $(uname -s) == "Linux" ]] || die "this deployer targets Linux production hosts; on macOS use Docker Desktop + the manual steps in DEPLOYMENT.md"
if [[ ${EUID} -ne 0 ]]; then
  command -v sudo >/dev/null 2>&1 || die "must run as root (try: sudo scripts/deploy.sh)"
  log "not root — re-running with sudo"
  exec sudo bash "$0" "$@"
fi
[[ -f docker-compose.yml ]] || die "run from the repo (docker-compose.yml not found next to scripts/)"

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

pkg_install() { # pkg_install <pkg...>
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
    # Host-only rewrite keeps the original scheme (http/https) and suite paths;
    # covers both classic *.list and deb822 *.sources (Ubuntu 24.04+).
    find -H /etc/apt -maxdepth 2 \
      \( -name '*.list' -o -name '*.sources' \) -type f -exec \
      sed -i -E "s#(https?://)(archive\.ubuntu\.com|cn\.archive\.ubuntu\.com|security\.ubuntu\.com|deb\.debian\.org|security\.debian\.org|mirrors\.cloud\.aliyuncs\.com)/#\1${APT_MIRROR}/#g" {} +
    log "apt sources rewritten to $APT_MIRROR"
    apt-get update
  fi
elif [[ -n $APT_MIRROR ]]; then
  log "not a Debian/Ubuntu host — skipping apt mirror rewrite"
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
    # Unknown/derivative distro: the upstream installer knows the edge cases
    # and has a built-in Aliyun mirror mode for CN hosts.
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
    log "Docker $(docker --version | awk '{print $3}' | tr -d ,) already working — skipping install"
  else
    install_docker
  fi
fi
command -v docker >/dev/null 2>&1 || die "docker not installed"
docker info >/dev/null 2>&1 || die "docker daemon not reachable (systemctl status docker?)"
docker compose version >/dev/null 2>&1 || die "docker compose plugin missing — install docker-compose-plugin"

# ---------------------------------------------- 3. registry mirrors ---------
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
    log "registry mirrors written to $CFG: ${REGISTRY_MIRRORS[*]}"
    systemctl restart docker 2>/dev/null || service docker restart
  elif command -v python3 >/dev/null 2>&1; then
    cp -a "$CFG" "${CFG}.bak-$(date +%Y%m%dT%H%M%S)"
    MIRRORS_JSON=$MIRRORS_JSON python3 - <<'PY'
import json, os, sys
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
  elif command -v jq >/dev/null 2>&1; then
    cp -a "$CFG" "${CFG}.bak-$(date +%Y%m%dT%H%M%S)"
    tmp=$(mktemp)
    jq --argjson m "$MIRRORS_JSON" '.["registry-mirrors"] = $m' "$CFG" > "$tmp" && mv "$tmp" "$CFG"
    log "registry mirrors merged into $CFG (backup kept alongside)"
    systemctl restart docker 2>/dev/null || service docker restart
  else
    warn "daemon.json exists but neither python3 nor jq is available to merge — add manually: \"registry-mirrors\": $MIRRORS_JSON"
  fi
fi

# ---------------------------------------------------- 4. generate .env -----
command -v openssl >/dev/null 2>&1 || pkg_install openssl

if [[ -f .env && $FORCE_ENV -eq 0 ]]; then
  log ".env already exists — keeping it (use --force-env to re-roll secrets)"
else
  if [[ -f .env ]]; then
    cp -a .env ".env.bak-$(date +%Y%m%dT%H%M%S)"
    warn "old .env backed up; re-rolling all secrets invalidates nothing in the data plane, but note POSTGRES/MINIO password changes need the old values for existing volumes"
  fi
  if [[ -z $BASE_URL ]]; then
    DEFAULT_URL="http://$(hostname -I 2>/dev/null | awk '{print $1}' || echo 127.0.0.1):8080"
    if [[ $ASSUME_YES -eq 0 && -t 0 ]]; then
      read -r -e -p "Public URL browsers use to reach the store (STORE_BASE_URL, e.g. https://store.example.com) [$DEFAULT_URL]: " BASE_URL
      BASE_URL=${BASE_URL:-$DEFAULT_URL}
    else
      BASE_URL=$DEFAULT_URL
      warn "no --base-url given; defaulting STORE_BASE_URL to $BASE_URL — set the real https:// domain in .env before going live"
    fi
  fi
  if [[ -z $MONITOR_URL ]]; then MONITOR_URL=$BASE_URL; fi

  {
    printf '# Generated by scripts/deploy.sh on %s — treat as secret.\n' "$(date -u +%FT%TZ)"
    printf 'STORE_TICKET_SECRET=%s\n'   "$(openssl rand -hex 32)"
    printf 'STORE_ROLLOUT_SECRET=%s\n'  "$(openssl rand -hex 32)"
    printf 'STORE_CLI_CLIENT_SECRET=%s\n' "$(openssl rand -hex 32)"
    printf 'STORE_BASE_URL=%s\n' "$BASE_URL"
    printf 'MONITOR_TARGET_BASE_URL=%s\n' "$MONITOR_URL"
    if [[ -n $ALERT_WEBHOOK ]]; then
      printf 'MONITOR_ALERT_WEBHOOK=%s\n' "$ALERT_WEBHOOK"
    else
      printf '#MONITOR_ALERT_WEBHOOK=\n'
    fi
    printf 'POSTGRES_DB=store\nPOSTGRES_USER=store\n'
    printf 'POSTGRES_PASSWORD=%s\n'    "$(openssl rand -hex 24)"
    printf 'MINIO_ROOT_USER=store\n'
    printf 'MINIO_ROOT_PASSWORD=%s\n'  "$(openssl rand -hex 24)"
    printf '#DOCKER_CPUS_LIMIT=2\n#DOCKER_MEM_LIMIT=1g\n'
  } > .env
  chmod 600 .env
  log ".env generated (secrets rolled, mode 600): STORE_BASE_URL=$BASE_URL"
fi

# ------------------------------------------------- 5. build & start --------
if [[ $NO_UP -eq 1 ]]; then
  log "--no-up: environment prepared; start later with: docker compose --profile app up -d --build"
  exit 0
fi

log "building and starting the stack (first build pulls Maven/npm deps — be patient)"
PROFILES=(--profile app)
if [[ $WITH_MONITOR -eq 1 ]]; then
  PROFILES+=(--profile monitor)
fi
docker compose "${PROFILES[@]}" up -d --build

wait_healthy() { # wait_healthy <service> <timeout-seconds>
  local svc=$1 timeout=$2 waited=0 cid st
  cid=$(docker compose ps -q "$svc")
  if [[ -z $cid ]]; then
    docker compose logs --tail 50 "$svc" || true
    die "container '$svc' is not running"
  fi
  while :; do
    st=$(docker inspect -f '{{if .State.Health}}{{.State.Health.Status}}{{else}}none{{end}}' "$cid" 2>/dev/null || echo starting)
    if [[ $st == healthy ]]; then
      log "$svc is healthy"
      return 0
    fi
    if [[ $st == unhealthy && $waited -ge 120 ]]; then
      docker compose logs --tail 50 "$svc" || true
      die "$svc reports unhealthy"
    fi
    if [[ $waited -ge $timeout ]]; then
      docker compose logs --tail 50 "$svc" || true
      die "$svc did not become healthy within ${timeout}s"
    fi
    waited=$((waited + 5))
    sleep 5
  done
}

wait_healthy store 600
if [[ $WITH_MONITOR -eq 1 ]]; then
  wait_healthy monitor 300
fi

log "verifying endpoints through the loopback bindings"
curl -fsS http://127.0.0.1:8080/actuator/health | grep -q '"UP"' || warn "store health endpoint did not report UP"
if [[ $WITH_MONITOR -eq 1 ]]; then
  curl -fsS http://127.0.0.1:8090/actuator/health >/dev/null   || warn "monitor health endpoint not reachable"
fi

# ---------------------------------------------------- 6. what's next -------
# Same guard as deploy-monitor.sh: a commented-out line in a hand-copied
# .env.example must not kill the script with a silent set-e exit here.
STORE_URL=$(grep -E '^STORE_BASE_URL=' .env | cut -d= -f2- || true)
STORE_URL=${STORE_URL:-<set STORE_BASE_URL in .env>}
cat <<SUMMARY

Deployment is up. Remaining wiring (see DEPLOYMENT.md):

  1. Reverse proxy / WAF (tested: SafeLine) — two sites, HTTP upstreams:
       store    -> http://127.0.0.1:8080   (public: $STORE_URL)
       monitor  -> http://127.0.0.1:8090
     Set the store site's request-body limit to >= 1073741824 (1 GiB) — the
     WAF default is far lower and large artifact uploads will fail with 413.
  2. TLS terminates at the proxy; point STORE_BASE_URL in .env at the public
     https:// domain, then: docker compose --profile app up -d
  3. Backups — add to root's crontab (KEEP_DAYS=14 keeps two weeks):
       30 3 * * * cd $PWD && KEEP_DAYS=14 ./scripts/backup-stack.sh /mnt/backups/infinia
  4. Status monitor — split deployment (default): run it on its OWN host so
     the status page survives a store outage:
       git clone <this repo>; cd infinia-store-platform
       printf 'MONITOR_TARGET_BASE_URL=%s\n' "$STORE_URL" > .env
       docker compose -f docker-compose.monitor.yml up -d
     (single-host install instead: re-run this script with --with-monitor)
  5. Useful commands:
       docker compose ps
       docker compose logs -f store
       git pull && docker compose --profile app up -d --build   # upgrade

SUMMARY
