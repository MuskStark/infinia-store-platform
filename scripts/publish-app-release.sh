#!/usr/bin/env bash
# Publish a FengYu host release into the Infinia store (main-application updates).
#
# Takes a directory of release assets named after the FengYu release matrix —
#   Infinia-<v>-win-x64-setup.exe        (installed, lite)
#   Infinia-<v>-win-x64-portable.zip     (portable,  lite)
#   Infinia-JRE-<v>-win-x64-setup.exe    (installed, bundled JRE)
#   Infinia-<v>-mac-arm64.dmg            (installed, lite)
#   Infinia-<v>-linux-x64.deb            (installed, lite)
#   Infinia-<v>-linux-x64.AppImage       (portable,  lite)
#   Infinia-UOS-<v>-linux-x64.deb        (installed, UOS build)
#   Infinia-<v>-web.zip / .tar.gz        (portable web distribution)
#   Infinia.jar                          (portable fat JAR)
# — infers kind/platform/arch/variant exactly like the server does, uploads each
# asset through the presigned pipeline and submits the release for review.
#
# Usage:
#   scripts/publish-app-release.sh <version> <assets-dir> [channel]
# Environment:
#   STORE_BASE            store origin          (default http://localhost:8080)
#   STORE_CLI_CLIENT_ID   OAuth client id       (default store-cli)
#   STORE_CLI_CLIENT_SECRET  OAuth client secret (default dev-only-cli-secret)
#   STORE_APP_NAMESPACE   listing namespace     (default official)
#   STORE_APP_SLUG        listing slug          (default fengyu-host)
#   STORE_SUBMIT          1 = submit for review (default 1)
set -euo pipefail

VERSION="${1:?usage: publish-app-release.sh <version> <assets-dir> [channel]}"
ASSETS_DIR="${2:?usage: publish-app-release.sh <version> <assets-dir> [channel]}"
CHANNEL="${3:-stable}"
STORE_BASE="${STORE_BASE:-http://localhost:8080}"
CLIENT_ID="${STORE_CLI_CLIENT_ID:-store-cli}"
CLIENT_SECRET="${STORE_CLI_CLIENT_SECRET:-dev-only-cli-secret}"
NAMESPACE="${STORE_APP_NAMESPACE:-official}"
SLUG="${STORE_APP_SLUG:-fengyu-host}"
SUBMIT="${STORE_SUBMIT:-1}"

[ -d "$ASSETS_DIR" ] || { echo "assets dir not found: $ASSETS_DIR" >&2; exit 1; }

log() { printf '\033[1;34m==>\033[0m %s\n' "$*"; }
die() { printf '\033[1;31merror:\033[0m %s\n' "$*" >&2; exit 1; }

api() { # api METHOD PATH [JSON_BODY] → response body
  local method="$1" path="$2" body="${3:-}"
  if [ -n "$body" ]; then
    curl -sS -X "$method" -H "Authorization: Bearer $TOKEN" \
      -H 'Content-Type: application/json' -d "$body" "$STORE_BASE$path"
  else
    curl -sS -X "$method" -H "Authorization: Bearer $TOKEN" "$STORE_BASE$path"
  fi
}

log "authenticating $CLIENT_ID against $STORE_BASE"
TOKEN=$(curl -sS -X POST "$STORE_BASE/oauth2/token" \
  --data-urlencode "grant_type=client_credentials" \
  --data-urlencode "client_id=$CLIENT_ID" \
  --data-urlencode "client_secret=$CLIENT_SECRET" \
  | jq -r '.access_token // empty')
[ -n "$TOKEN" ] || die "client_credentials grant failed"
log "token acquired"

# ---- find or create the APP listing -----------------------------------------
LISTING_ID=$(curl -sS -o /dev/null -w '%{http_code}' \
  "$STORE_BASE/api/v1/listings/$NAMESPACE/$SLUG")
if [ "$LISTING_ID" = "200" ]; then
  LISTING_ID=$(curl -sS "$STORE_BASE/api/v1/listings/$NAMESPACE/$SLUG" | jq -r '.listingId')
  log "listing $NAMESPACE/$SLUG exists ($LISTING_ID)"
else
  log "creating listing $NAMESPACE/$SLUG (namespace reserved on first use)"
  ORG=$(api POST /api/v1/organizations "{\"slug\":\"$NAMESPACE\",\"name\":\"Infinia Official\"}")
  echo "$ORG" | jq -e '.slug // error("organization rejected: \(.)")' >/dev/null
  CREATED=$(api POST /api/v1/publisher/listings "$(jq -n \
    --arg ns "$NAMESPACE" --arg slug "$SLUG" \
    '{namespace:$ns, slug:$slug, type:"APP", category:"Productivity",
      tags:["host","official"], name:"Infinia Host",
      summary:"The local-first FengYu host application."}')")
  LISTING_ID=$(echo "$CREATED" | jq -r '.listingId // empty')
  [ -n "$LISTING_ID" ] || die "listing creation failed: $CREATED"
  log "listing created ($LISTING_ID)"
fi

# ---- draft release -----------------------------------------------------------
DRAFT=$(api POST "/api/v1/publisher/listings/$LISTING_ID/releases" "$(jq -n \
  --arg v "$VERSION" --arg ch "$CHANNEL" --arg cl "Infinia host release $VERSION" \
  '{version:$v, channel:$ch, license:"GPL-3.0", changelogMarkdown:$cl}')")
RELEASE_ID=$(echo "$DRAFT" | jq -r '.releaseId // empty')
[ -n "$RELEASE_ID" ] || die "draft creation failed: $DRAFT"
# A failure past this point leaves the draft (and any uploaded assets) behind —
# tell the operator instead of letting a half-published release rot invisibly.
# EXIT + flag (not ERR): `cmd || die` chains never fire ERR traps.
FINISHED=0
trap 'if [ "$FINISHED" != 1 ] && [ -n "${RELEASE_ID:-}" ]; then
  printf "\033[1;33mwarn:\033[0m draft release %s (%s) left in DRAFT — resume or delete it in the publisher console\n" "$RELEASE_ID" "$VERSION" >&2
fi' EXIT
log "draft release $VERSION created ($RELEASE_ID)"

# ---- upload every release asset ----------------------------------------------
shopt -s nullglob
UPLOADED=0
for FILE in "$ASSETS_DIR"/*; do
  BASENAME=$(basename "$FILE")
  case "$BASENAME" in
    *.exe|*.msi|*.dmg|*.pkg|*.deb|*.zip|*.tar.gz|*.AppImage|*.jar) ;;
    *) log "skip $BASENAME (not a distribution asset)"; continue ;;
  esac
  SIZE=$(stat -f%z "$FILE" 2>/dev/null || stat -c%s "$FILE")
  log "uploading $BASENAME ($(printf '%d MiB' $((SIZE / 1048576))))"
  SESSION=$(api POST "/api/v1/publisher/releases/$RELEASE_ID/uploads" "$(jq -n \
    --arg f "$BASENAME" --argjson s "$SIZE" '{filename:$f, size:$s}')")
  UPLOAD_URL=$(echo "$SESSION" | jq -r '.uploadUrl // empty')
  [ -n "$UPLOAD_URL" ] || die "upload session failed for $BASENAME: $SESSION"
  KIND=$(echo "$SESSION" | jq -r '.kind')
  PLATFORM=$(echo "$SESSION" | jq -r '.platform')
  VARIANT=$(echo "$SESSION" | jq -r '.variant')
  STATUS=$(curl -sS -o /dev/null -w '%{http_code}' -X PUT \
    -H 'Content-Type: application/octet-stream' --data-binary "@$FILE" \
    "$STORE_BASE$UPLOAD_URL")
  [ "$STATUS" = "204" ] || die "PUT $BASENAME failed with $STATUS"
  printf '     %-42s %s/%s %s %s\n' "$BASENAME" "$PLATFORM" "$KIND" "variant=$VARIANT"
  UPLOADED=$((UPLOADED + 1))
done
[ "$UPLOADED" -gt 0 ] || die "no distribution assets found in $ASSETS_DIR"
log "$UPLOADED assets uploaded"

# ---- submit for review --------------------------------------------------------
if [ "$SUBMIT" = "1" ]; then
  # curl exits 0 on HTTP 4xx/5xx — check the status or a rejected submission
  # reports "submitted" and the operator ships nothing.
  STATUS=$(curl -sS -o /dev/null -w '%{http_code}' -X POST \
    -H "Authorization: Bearer $TOKEN" \
    "$STORE_BASE/api/v1/publisher/releases/$RELEASE_ID/submit")
  case "$STATUS" in
    2*) FINISHED=1 ;;
    *) die "submit failed with HTTP $STATUS" ;;
  esac
  log "release $VERSION submitted — awaiting reviewer approval before PUBLISHED"
else
  FINISHED=1
  log "STORE_SUBMIT=0 — release $VERSION left in DRAFT"
fi
log "release processing complete at $STORE_BASE — updates require approval and a supported channel"
