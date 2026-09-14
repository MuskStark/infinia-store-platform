#!/usr/bin/env bash
#
# One-click Cloudflare Pages bootstrap/publish for the SPA's static assets
# (DEPLOYMENT.md "Static assets on Cloudflare Pages" — the first-visit 522
# fix). One script, three ways:
#
#   full run (auto-detects the host):
#     store host (new or old) — repo checkout + .env + docker compose:
#       sets ASSETS_BASE_URL in .env, builds the next image, extracts the SPA
#       from that image's jar and publishes it — Pages receives byte-identical
#       files to what the jar will serve. The running container is untouched
#       until --switch (and only after the assets domain actually answers).
#     any other host (laptop / fresh box, docker only):
#       builds store-web inside a throwaway node container from the same
#       lockfile with the same ASSETS_BASE_URL and publishes that. Hashes match
#       the server build because the value and the lockfile are identical.
#
#   publish-only:
#     --dist <dir> — publish an already-built dist and exit.
#
# Configuration — precedence: flags > environment variables > deploy.conf >
# built-in defaults. deploy.conf (repo root, gitignored, mode 600) carries:
#
#   ASSETS_BASE_URL=https://assets.example.com   # required for full runs
#   ASSETS_PAGES_PROJECT=infinia-assets
#   CLOUDFLARE_ACCOUNT_ID=<id>                   # required
#   CLOUDFLARE_API_TOKEN=<token>                 # required; Pages · Edit
#   NPM_CONFIG_REGISTRY=https://registry.npmmirror.com   # optional
#   NODE_IMAGE=node:22-alpine                    # optional
#
# Interactive: on a terminal, missing required values trigger a questionnaire
# that can save the answers to deploy.conf. `--configure` runs ONLY that
# questionnaire (and exits) — the way to pre-provision a host non-interactively
# is piping answers into it.
#
# Usage:
#   scripts/deploy-assets.sh --all    # EVERYTHING, in order: configure
#                                     # (questionnaire if values are missing),
#                                     # build & publish, attach the custom
#                                     # domain via the Cloudflare API, wait
#                                     # for it to serve the files, switch the
#                                     # store container and wait for health —
#                                     # the one-command path
#   scripts/deploy-assets.sh          # publish + attach the domain, then run
#                                     # again with --switch to cut over
#   scripts/deploy-assets.sh --dist store-web/dist     # publish-only
#   scripts/deploy-assets.sh --configure               # interactive setup only
#
# Options:
#   --all              do everything end-to-end (implies --switch on the store
#                      host; on other hosts it publishes + attaches and tells
#                      you the final command to run on the server)
#   --base-url URL    assets origin (or ASSETS_BASE_URL env / deploy.conf /
#                      the existing .env on the store host)
#   --dist DIR        publish this already-built dist and exit (no build,
#                     no .env changes, no --switch)
#   --project NAME    Pages project (default infinia-assets)
#   --switch          after publishing, recreate the store container with the
#                     new image — only once the domain serves the files
#                     (store host only; with --all the domain is attached and
#                     waited for automatically)
#   --configure       run the interactive questionnaire, save deploy.conf, exit
#   --from-image      force the store-host path (extract from built image)
#   --from-build      force the container-build path (any host)
#   --keep-dir        keep the temporary publish directory (debugging)
#
# Environment:
#   CLOUDFLARE_API_TOKEN   Pages:Edit token (or deploy.conf)
#   CLOUDFLARE_ACCOUNT_ID  account id (or deploy.conf)
#   PAGES_INIT             default 1 — create the project if absent (idempotent)
#   NPM_CONFIG_REGISTRY    npm mirror for the wrangler download
#   NODE_IMAGE             default node:22-alpine
#
# Idempotent: re-running rebuilds (layer-cached), re-extracts and re-publishes
# additively — hashed files accumulate, so older shells keep resolving.
set -euo pipefail
cd "$(dirname "$0")/.."

log()  { printf '==> %s\n' "$*"; }
warn() { printf 'WARNING: %s\n' "$*" >&2; }
die()  { printf 'error: %s\n' "$*" >&2; exit 1; }

BASE_URL=""
PROJECT=""
MODE=auto
DIST_ARG=""
SWITCH=0
KEEP_DIR=0
CONFIGURE=0
ALL=0

while [[ $# -gt 0 ]]; do
  case $1 in
    --base-url)   BASE_URL=${2:?}; shift 2 ;;
    --dist)       DIST_ARG=${2:?}; shift 2 ;;
    --project)    PROJECT=${2:?}; shift 2 ;;
    --switch)     SWITCH=1; shift ;;
    --all)        ALL=1; SWITCH=1; shift ;;
    --configure)  CONFIGURE=1; shift ;;
    --from-image) MODE=image; shift ;;
    --from-build) MODE=build; shift ;;
    --keep-dir)   KEEP_DIR=1; shift ;;
    -h|--help)    awk 'NR==1{next} /^set -e/{exit} {sub(/^# ?/, ""); print}' "$0"; exit 0 ;;
    *)            die "unknown option: $1 (see --help)" ;;
  esac
done

# ---------------------------------------------------- configuration ---------
CONFIG_FILE=deploy.conf
INTERACTIVE=0
[[ -t 0 ]] && INTERACTIVE=1

load_config() { # KEY=VALUE file; real environment variables keep precedence
  [[ -f $CONFIG_FILE ]] || return 0
  local line key val
  while IFS= read -r line; do
    [[ $line =~ ^[[:space:]]*(#|$) ]] && continue
    key=${line%%=*}; val=${line#*=}
    [[ $key =~ ^[A-Za-z_][A-Za-z0-9_]*$ ]] || continue
    [[ -n ${!key+x} ]] || export "$key=$val"
  done < "$CONFIG_FILE"
}
load_config

# flag > environment > deploy.conf > built-in default
BASE_URL=${BASE_URL:-${ASSETS_BASE_URL:-}}
PROJECT=${PROJECT:-${ASSETS_PAGES_PROJECT:-infinia-assets}}
NODE_IMAGE=${NODE_IMAGE:-node:22-alpine}

# Store-host last resort: the running deployment's .env already names the
# origin (kept there by the image mode below / upgrade.sh's re-publish).
if [[ -z ${ASSETS_BASE_URL:-} && -f .env ]]; then
  ASSETS_BASE_URL=$(grep -E '^ASSETS_BASE_URL=' .env | head -1 | cut -d= -f2- || true)
  BASE_URL=${BASE_URL:-${ASSETS_BASE_URL:-}}
fi

ask() { # ask <prompt> [default] — answer in REPLY (stdin-friendly)
  local prompt=$1 def=${2:-}
  if [[ $INTERACTIVE -eq 1 ]]; then
    if [[ -n $def ]]; then
      read -r -e -p "$prompt [$def]: " REPLY || die "no input available — pre-provision via '$0 --configure' with piped answers"
      REPLY=${REPLY:-$def}
    else
      read -r -e -p "$prompt: " REPLY || die "no input available — pre-provision via '$0 --configure' with piped answers"
    fi
  else
    if [[ -n $def ]]; then
      read -r -p "$prompt [$def]: " REPLY || die "no input available"
      REPLY=${REPLY:-$def}
    else
      read -r -p "$prompt: " REPLY || die "no input available"
    fi
  fi
}

ask_secret() { # hidden on a terminal, plain on a pipe
  if [[ $INTERACTIVE -eq 1 ]]; then
    read -r -s -p "$1: " REPLY || die "no input available"
  else
    read -r -p "$1: " REPLY || die "no input available"
  fi
  printf '\n' >&2
}

upsert_conf() { # upsert_conf <key> <value> — into deploy.conf
  local key=$1 val=$2
  if [[ -f $CONFIG_FILE ]] && grep -q "^${key}=" "$CONFIG_FILE"; then
    sed -i.bak "s|^${key}=.*|${key}=${val}|" "$CONFIG_FILE" && rm -f "$CONFIG_FILE.bak"
  else
    printf '%s=%s\n' "$key" "$val" >> "$CONFIG_FILE"
  fi
}

configure_assets() { # configure_assets <need-base-url 0|1> — fill + optionally save
  local need_base=$1
  log "configuration — missing values (answers can be saved to $CONFIG_FILE)"
  if [[ $need_base -eq 1 && -z ${ASSETS_BASE_URL:-} ]]; then
    while :; do
      ask "Assets origin URL (absolute https://, e.g. https://assets.infinia.fyi)" "https://assets.infinia.fyi"
      if [[ $REPLY == https://* ]]; then export ASSETS_BASE_URL=$REPLY; break; fi
      warn "must start with https://"
    done
  fi
  if [[ -z ${CLOUDFLARE_ACCOUNT_ID:-} ]]; then
    ask "Cloudflare Account ID (dashboard: Workers & Pages → right sidebar)"
    [[ -n $REPLY ]] || die "account id is required"
    export CLOUDFLARE_ACCOUNT_ID=$REPLY
  fi
  if [[ -z ${CLOUDFLARE_API_TOKEN:-} ]]; then
    ask_secret "Cloudflare API token (permission: Cloudflare Pages · Edit)"
    [[ -n $REPLY ]] || die "API token is required"
    export CLOUDFLARE_API_TOKEN=$REPLY
  fi
  ask "Save these settings to $CONFIG_FILE for future runs" "Y"
  if [[ $REPLY == [Yy]* ]]; then
    upsert_conf ASSETS_BASE_URL "${ASSETS_BASE_URL:-}"
    upsert_conf ASSETS_PAGES_PROJECT "$PROJECT"
    upsert_conf CLOUDFLARE_ACCOUNT_ID "$CLOUDFLARE_ACCOUNT_ID"
    upsert_conf CLOUDFLARE_API_TOKEN "$CLOUDFLARE_API_TOKEN"
    chmod 600 "$CONFIG_FILE"
    log "saved to $CONFIG_FILE (mode 600, gitignored — it holds the token)"
  else
    log "not saving — values apply to this run only"
  fi
}

# Required values: publish-only mode needs the Cloudflare pair; full runs also
# need the assets origin. Missing + terminal (or --configure) → questionnaire;
# missing + unattended → fail fast with the configuration hints.
NEED_BASE_URL=1
[[ -n $DIST_ARG ]] && NEED_BASE_URL=0
MISSING=()
[[ -z ${CLOUDFLARE_API_TOKEN:-} ]] && MISSING+=('CLOUDFLARE_API_TOKEN')
[[ -z ${CLOUDFLARE_ACCOUNT_ID:-} ]] && MISSING+=('CLOUDFLARE_ACCOUNT_ID')
[[ $NEED_BASE_URL -eq 1 && -z ${ASSETS_BASE_URL:-} ]] && MISSING+=('ASSETS_BASE_URL')
if [[ ${#MISSING[@]} -gt 0 ]]; then
  if [[ $INTERACTIVE -eq 1 || $CONFIGURE -eq 1 ]]; then
    configure_assets "$NEED_BASE_URL"
    BASE_URL=${BASE_URL:-${ASSETS_BASE_URL:-}}
  else
    die "missing configuration: ${MISSING[*]}
  fix one of three ways — flag (--base-url/--project), environment variable,
  or a $CONFIG_FILE file; '$0 --configure' runs an interactive questionnaire"
  fi
fi
if [[ $CONFIGURE -eq 1 ]]; then
  log "configuration complete — rerun without --configure to publish"
  exit 0
fi
export PAGES_INIT=${PAGES_INIT:-1} ASSETS_PAGES_PROJECT=$PROJECT

# wrangler without a host Node toolchain: prefer local npx, else run it in a
# throwaway node container (the Docker-only store host).
wrangler_create() { # create the Pages project if absent
  if command -v npx >/dev/null; then
    npx --yes wrangler@4 pages project create "$PROJECT" --production-branch=main
  else
    command -v docker >/dev/null || die "wrangler needs npx or docker on the host"
    docker run --rm \
      -e CLOUDFLARE_API_TOKEN -e CLOUDFLARE_ACCOUNT_ID -e NPM_CONFIG_REGISTRY \
      "$NODE_IMAGE" npx --yes wrangler@4 pages project create "$PROJECT" --production-branch=main
  fi
}

wrangler_deploy() { # wrangler_deploy <dist-abs> — publish the directory
  local dist=$1
  if command -v npx >/dev/null; then
    npx --yes wrangler@4 pages deploy --project-name="$PROJECT" --branch=main \
      --commit-dirty=true "$dist"
  else
    command -v docker >/dev/null || die "wrangler needs npx or docker on the host"
    docker run --rm -v "$dist:/dist:ro" -w /dist \
      -e CLOUDFLARE_API_TOKEN -e CLOUDFLARE_ACCOUNT_ID -e NPM_CONFIG_REGISTRY \
      "$NODE_IMAGE" npx --yes wrangler@4 pages deploy \
      --project-name="$PROJECT" --branch=main --commit-dirty=true /dist
  fi
}

# Publish a built dist (dist layout at its root) to the Pages project.
publish_dist() { # publish_dist <dist-dir>
  local dist=$1
  test -f "$dist/index.html" || { echo "error: $dist/index.html not found — run 'yarn web:build' first" >&2; return 1; }
  local dist_abs
  dist_abs=$(cd "$dist" && pwd)

  # Visibility check: which base mode is this dist carrying?
  if grep -q 'src="https://[^"]*/assets/' "$dist_abs/index.html"; then
    log "dist uses an absolute assets origin (matches a server built with ASSETS_BASE_URL set)"
  else
    log "NOTE: dist references same-origin /assets/* — if the server runs with ASSETS_BASE_URL set, rebuild with the same value before publishing"
  fi

  if [[ ${PAGES_INIT:-} == 1 ]]; then
    log "creating Pages project $PROJECT (production branch main) if absent"
    # "already exists" is success for the idempotent one-time path.
    wrangler_create || log "project create reported an error (usually: already exists) — continuing"
  fi

  log "publishing $dist_abs to Pages project $PROJECT (branch main)"
  wrangler_deploy "$dist_abs"
}

# ---- publish-only mode (CI): --dist <dir> -----------------------------------
if [[ -n $DIST_ARG ]]; then
  [[ -z $BASE_URL ]] || warn "--base-url ignored in --dist mode (dist is already built)"
  [[ $SWITCH -eq 0 ]] || die "--switch/--all do not combine with --dist"
  publish_dist "$DIST_ARG"
  log "done. One-time wiring if not yet done: bind the custom domain"
  log "(e.g. assets.infinia.fyi) to $PROJECT in the Pages project settings."
  exit 0
fi

# ---- full mode: resolve inputs ----------------------------------------------
BASE_URL=${BASE_URL%/}   # stored/probed without the trailing slash
[[ -n $BASE_URL ]] || die "--base-url (or ASSETS_BASE_URL) is required, e.g. --base-url https://assets.infinia.fyi"
[[ $BASE_URL == http* ]] || die "--base-url must be an absolute https:// URL"
command -v docker >/dev/null || die "docker is required"

# ---- pick the mode ----------------------------------------------------------
if [[ $MODE == auto ]]; then
  if [[ -f .env ]] && docker compose --profile app config --services 2>/dev/null | grep -qx store; then
    MODE=image
  else
    MODE=build
  fi
fi
if [[ $SWITCH -eq 1 && $MODE != image ]]; then
  if [[ $ALL -eq 1 ]]; then
    warn "--all on a non-store host: publishing, domain attach and CI wiring only —"
    warn "finish by running the same command on the store host"
    SWITCH=0
  else
    die "--switch only applies on the store host (image mode)"
  fi
fi

TMPDIST=$(mktemp -d "${TMPDIR:-/tmp}/infinia-assets.XXXXXX")
cleanup() { [[ $KEEP_DIR -eq 1 ]] && log "keeping $TMPDIST (--keep-dir)" || rm -rf "$TMPDIST"; }
trap cleanup EXIT

# Extract the SPA out of a built image's jar into $1 (dist layout at its root).
extract_jar_static() {
  local jar=$1 dest=$2
  if command -v unzip >/dev/null; then
    (cd "$dest" && unzip -q "$jar" 'BOOT-INF/classes/static/*' \
      && mv BOOT-INF/classes/static/* . \
      && rmdir BOOT-INF/classes/static BOOT-INF/classes BOOT-INF 2>/dev/null || true)
  else
    python3 - "$jar" "$dest" <<'PY'
import sys, zipfile, os, shutil
jar, dest = sys.argv[1], sys.argv[2]
prefix = 'BOOT-INF/classes/static/'
with zipfile.ZipFile(jar) as z:
    for n in z.namelist():
        if n.startswith(prefix) and not n.endswith('/'):
            z.extract(n, dest)
src = os.path.join(dest, 'BOOT-INF/classes/static')
for e in os.listdir(src):
    shutil.move(os.path.join(src, e), os.path.join(dest, e))
PY
    rm -rf "$dest/BOOT-INF"
  fi
}

if [[ $MODE == image ]]; then
  # ---- store host: publish exactly what the next jar embeds -----------------
  log "store host: upserting ASSETS_BASE_URL=$BASE_URL in .env"
  if grep -q '^ASSETS_BASE_URL=' .env; then
    sed -i.bak "s|^ASSETS_BASE_URL=.*|ASSETS_BASE_URL=$BASE_URL|" .env && rm -f .env.bak
  else
    printf '\nASSETS_BASE_URL=%s\n' "$BASE_URL" >> .env
  fi

  log "building the next image (the running container keeps serving)"
  docker compose --profile app build store

  log "extracting the SPA from the freshly built image's jar"
  docker create --name web-extract-$$ infinia-webservice >/dev/null
  trap 'docker rm -f web-extract-$$ >/dev/null 2>&1 || true; cleanup' EXIT
  docker cp web-extract-$$:/app/InfiniaWebService.jar "$TMPDIST/web.jar"
  docker rm web-extract-$$ >/dev/null
  extract_jar_static "$TMPDIST/web.jar" "$TMPDIST" && rm -f "$TMPDIST/web.jar"
  DIST=$TMPDIST
else
  # ---- any host: build in a node container from the same lockfile -----------
  log "building store-web in a $NODE_IMAGE container (same lockfile, same base URL)"
  warn "this creates node_modules/ inside the repo checkout (gitignored)"
  docker run --rm \
    -v "$PWD":/ws -w /ws \
    -e ASSETS_BASE_URL="$BASE_URL" \
    -e NPM_CONFIG_REGISTRY -e COREPACK_NPM_REGISTRY \
    "$NODE_IMAGE" sh -c \
      'corepack enable && yarn install --immutable && yarn workspace @infinia/store-web build'
  DIST=$PWD/store-web/dist
fi

test -f "$DIST/index.html" || die "no dist/index.html under $DIST — build failed?"
publish_dist "$DIST"

# ---- domain: attach via API, then wait until it actually serves ------------
DOMAIN=${BASE_URL#https://}; DOMAIN=${DOMAIN%%/*}
ENTRY=$(ls "$DIST/assets" | grep -E '^index-[^/]+\.js$' | head -1)
PROBE_URL="$BASE_URL/assets/$ENTRY"

attach_domain() { # attach $DOMAIN to the Pages project — idempotent
  log "attaching custom domain $DOMAIN to Pages project $PROJECT"
  local status body
  status=$(curl -sS -o "$TMPDIST/pages-domain.json" -w '%{http_code}' -X PUT \
    "https://api.cloudflare.com/client/v4/accounts/$CLOUDFLARE_ACCOUNT_ID/pages/projects/$PROJECT/domains/$DOMAIN" \
    -H "Authorization: Bearer $CLOUDFLARE_API_TOKEN" \
    -H 'Content-Type: application/json' --data '{}')
  body=$(cat "$TMPDIST/pages-domain.json" 2>/dev/null || true)
  if [[ $status == 2* ]] && printf '%s' "$body" | grep -q '"success":[[:space:]]*true'; then
    log "domain attached — DNS record + certificate are provisioned by Cloudflare"
  elif printf '%s' "$body" | grep -qiE 'already|exist'; then
    log "domain already attached"
  else
    warn "domain attach returned HTTP $status — if the zone lives on another Cloudflare account,"
    warn "bind $DOMAIN in the Pages project settings (dashboard) instead"
  fi
}
attach_domain

wait_for_domain() { # 0 once $PROBE_URL serves; ~10 min of retries
  local tries=40 i=1
  until curl -fsI --max-time 20 "$PROBE_URL" >/dev/null 2>&1; do
    if [[ $i -ge $tries ]]; then return 1; fi
    [[ $((i % 4)) -eq 1 ]] && log "waiting for the domain to serve the assets (DNS/cert provisioning, up to ~10 min) — attempt $i/$tries"
    sleep 15
    i=$((i + 1))
  done
  return 0
}

if [[ $SWITCH -eq 1 ]]; then
  log "waiting for $PROBE_URL"
  if ! wait_for_domain; then
    cat <<NOTICE

Domain still not serving after ~10 min — DNS/cert provisioning is unusually
slow; NOT switching. Check Workers & Pages → $PROJECT → Custom domains in
the dashboard, then re-run: scripts/deploy-assets.sh --switch
NOTICE
    exit 1
  fi
  log "domain is serving the published assets"
  log "switching the store to the new image"
  docker compose --profile app up -d
  log "waiting for the store to report healthy (best effort)"
  ok=0
  for _ in $(seq 1 60); do
    if curl -fsS --max-time 3 http://127.0.0.1:8080/actuator/health 2>/dev/null | grep -q '"UP"'; then ok=1; break; fi
    sleep 5
  done
  [[ $ok -eq 1 ]] && log "store is healthy — hard-refresh the site and verify assets load from $BASE_URL" \
                   || warn "store did not report UP within 300s — check: docker compose ps && docker compose logs --tail 50 store"
else
  if curl -fsI --max-time 20 "$PROBE_URL" >/dev/null 2>&1; then
    log "domain is already serving the published assets"
  else
    log "domain provisioning started — DNS/cert usually take a few minutes"
  fi
  # upgrade.sh sets DEPLOY_ASSETS_HOOK=1 for its post-deploy re-publish: the
  # cutover hint does not apply there (the new container already runs).
  if [[ ${DEPLOY_ASSETS_HOOK:-} != 1 ]]; then
    cat <<NEXT

Assets published to Pages. To cut the store over (store host):
  scripts/deploy-assets.sh --switch        # waits for the domain, then switches
NEXT
  fi
fi
