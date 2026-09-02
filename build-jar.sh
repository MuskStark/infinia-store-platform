#!/usr/bin/env bash
#
# Build the single deployable jar: Store Web's Vite output is embedded as static
# resources inside the executable Spring Boot jar, so one archive serves the SPA,
# the REST API and the OAuth authorization server from a single origin.
#
# Usage:
#   ./build-jar.sh [--skip-tests] [--skip-web]
#
#   --skip-tests   package without running backend tests
#   --skip-web     reuse the existing store-web/dist instead of rebuilding it
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
    echo "==> Building Store Web (Vite)"
    yarn web:build
fi

# The pom silently skips the embedded SPA when dist/ is absent (so plain backend
# builds work without Node) — a release jar must not ship that way, so verify.
[ -f store-web/dist/index.html ] \
    || fail "store-web/dist/index.html not found; rebuild without --skip-web"

echo "==> Packaging store-application"
MAVEN_ARGS=(-pl store-application -am package)
if [ "$SKIP_TESTS" -eq 1 ]; then
    MAVEN_ARGS+=(-DskipTests)
fi
./mvnw "${MAVEN_ARGS[@]}"

JAR=$(ls store-application/target/store-application-*.jar 2>/dev/null | grep -v '\.original$' | head -1)
[ -n "$JAR" ] || fail "no jar produced in store-application/target"
# Buffer the listing first: `unzip -l | grep -q` breaks under pipefail because
# grep -q exits on the first match and SIGPIPEs the archiver.
if command -v unzip >/dev/null 2>&1; then
    ENTRIES=$(unzip -l "$JAR")
else
    ENTRIES=$(jar tf "$JAR")
fi
grep -q 'BOOT-INF/classes/static/index.html' <<<"$ENTRIES" \
    || fail "jar does not contain the embedded SPA (store-web/dist)"

echo "==> OK: $JAR ($(du -h "$JAR" | cut -f1))"
echo "    Run: java -jar $JAR --spring.profiles.active=local"
