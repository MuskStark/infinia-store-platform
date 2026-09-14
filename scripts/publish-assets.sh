#!/usr/bin/env bash
#
# Publish the built store-web SPA to the Cloudflare Pages project that serves
# the assets origin (e.g. https://assets.infinia.fyi — DEPLOYMENT.md "Static
# assets on Cloudflare Pages"). Pages serves /assets/* with a year of immutable
# caching and never touches the store origin, which removes static assets from
# the flaky Cloudflare→origin path behind first-visit 522s.
#
# The uploaded dist MUST have been built with the same ASSETS_BASE_URL the
# server image bakes in (docker-compose build arg) — a different value changes
# the bundle bytes and therefore the content hashes the shell references.
#
# Usage:
#   scripts/publish-assets.sh [dist-dir]     # default store-web/dist
#
# Environment:
#   CLOUDFLARE_API_TOKEN   required; token with "Cloudflare Pages — Edit"
#   CLOUDFLARE_ACCOUNT_ID  required
#   ASSETS_PAGES_PROJECT   Pages project name (default infinia-assets)
#   PAGES_INIT=1           create the project first (idempotent; one-time)
#
# Rollback-safe by design: uploads are additive — hashed files accumulate, so
# an older HTML shell keeps finding its files after any later publish.
set -euo pipefail
cd "$(dirname "$0")/.."

DIST=${1:-store-web/dist}
PROJECT=${ASSETS_PAGES_PROJECT:-infinia-assets}

log() { printf '==> %s\n' "$*"; }

test -f "$DIST/index.html" || { echo "error: $DIST/index.html not found — run 'yarn web:build' first" >&2; exit 1; }
: "${CLOUDFLARE_API_TOKEN:?CLOUDFLARE_API_TOKEN (Pages:Edit) is required}"
: "${CLOUDFLARE_ACCOUNT_ID:?CLOUDFLARE_ACCOUNT_ID is required}"

# Visibility check: which base mode is this dist carrying?
if grep -q 'src="https://[^"]*/assets/' "$DIST/index.html"; then
  log "dist uses an absolute assets origin (matches a server built with ASSETS_BASE_URL set)"
else
  log "NOTE: dist references same-origin /assets/* — if the server runs with ASSETS_BASE_URL set, rebuild with the same value before publishing"
fi

command -v npx >/dev/null || { echo "error: npx (Node.js) is required for wrangler" >&2; exit 1; }

if [[ ${PAGES_INIT:-} == 1 ]]; then
  log "creating Pages project $PROJECT (production branch main) if absent"
  # "already exists" is success for the idempotent one-time path.
  npx --yes wrangler@4 pages project create "$PROJECT" --production-branch=main \
    || log "project create reported an error (usually: already exists) — continuing"
fi

log "publishing $DIST to Pages project $PROJECT (branch main)"
npx --yes wrangler@4 pages deploy "$DIST" \
  --project-name="$PROJECT" --branch=main --commit-dirty=true

log "done. One-time wiring if not yet done: bind the custom domain"
log "(e.g. assets.infinia.fyi) to $PROJECT in the Pages project settings."
