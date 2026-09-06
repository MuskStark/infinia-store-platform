#!/usr/bin/env bash
#
# Build the standalone monitor jar: Monitor Web's Vite output is embedded as
# static resources inside the executable Spring Boot jar (store-monitor), so
# the monitor serves its own status UI and API from a single origin — on its
# own host, independent of the store (ADR-011).
#
# Usage:
#   ./build-monitor-jar.sh [--skip-tests] [--skip-web]
#
#   --skip-tests   package without running monitor tests
#   --skip-web     reuse the existing monitor-web/dist instead of rebuilding it
set -euo pipefail

cd "$(dirname "$0")"

SKIP_TESTS=0
SKIP_WEB=0
for arg in "$@"; do
    case "$arg" in
        --skip-tests) SKIP_TESTS=1 ;;
        --skip-web) SKIP_WEB=1 ;;
        *) echo "error: unknown option '$arg' (expected --skip-tests or --skip-web)" >&2; exit 2 ;;
    esac
done

fail() { echo "error: $*" >&2; exit 1; }

command -v node >/dev/null 2>&1 || fail "node >=20 is required (https://nodejs.org)"
command -v yarn >/dev/null 2>&1 || fail "yarn 4 is required (https://yarnpkg.com)"
command -v java >/dev/null 2>&1 || fail "a JDK 21 is required (mvnw needs javac)"
[ -x ./mvnw ] || fail "./mvnw is missing or not executable"

if [ "$SKIP_WEB" -eq 0 ]; then
    echo "==> Installing workspace dependencies"
    yarn install
    echo "==> Building Monitor Web (Vite)"
    yarn monitor:build
fi

# The pom silently skips the embedded SPA when dist/ is absent — a release jar
# must not ship that way, so verify.
[ -f monitor-web/dist/index.html ] \
    || fail "monitor-web/dist/index.html not found; rebuild without --skip-web"

echo "==> Packaging store-monitor"
MAVEN_ARGS=(-pl store-monitor -am package)
if [ "$SKIP_TESTS" -eq 1 ]; then
    MAVEN_ARGS+=(-DskipTests)
fi
./mvnw "${MAVEN_ARGS[@]}"

JAR=$(ls store-monitor/target/store-monitor-*.jar 2>/dev/null | grep -v '\.original$' | head -1)
[ -n "$JAR" ] || fail "no jar produced in store-monitor/target"
# Buffer the listing first: `unzip -l | grep -q` breaks under pipefail because
# grep -q exits on the first match and SIGPIPEs the archiver.
if command -v unzip >/dev/null 2>&1; then
    ENTRIES=$(unzip -l "$JAR")
else
    ENTRIES=$(jar tf "$JAR")
fi
grep -q 'BOOT-INF/classes/static/index.html' <<<"$ENTRIES" \
    || fail "jar does not contain the embedded SPA (monitor-web/dist)"

echo "==> OK: $JAR ($(du -h "$JAR" | cut -f1))"
echo "    Run: MONITOR_TARGET_BASE_URL=https://store.example.com java -jar $JAR"
