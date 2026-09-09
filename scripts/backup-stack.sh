#!/usr/bin/env bash
#
# Backup the compose stack's stateful data:
#   - PostgreSQL store database      -> store-<ts>.sql.gz   (pg_dump, custom-format-safe plain SQL)
#   - MinIO store-blobs bucket       -> store-blobs/        (mc mirror)
#   - Store key material (JWT RSA)   -> keys/               (docker compose cp)
#
# Usage:
#   scripts/backup-stack.sh <backup-dir>          # e.g. /mnt/backups/infinia
# Environment:
#   KEEP_DAYS   prune backups older than N days  (default 14)
#
# Schedule on the production host, e.g. /etc/cron.d:
#   30 3 * * * root cd /opt/infinia-store && ./scripts/backup-stack.sh /mnt/backups/infinia
#
# Restore (documented in DEPLOYMENT.md):
#   gunzip -c store-<ts>.sql.gz | docker compose exec -T postgres psql
#   # blobs: run `mc mirror /backup/store-blobs dst/store-blobs` from an mc host
#   # keys:  docker compose cp <dir>/keys store:/var/lib/infinia-store/
set -euo pipefail

cd "$(dirname "$0")/.."

BACKUP_DIR=${1:?usage: backup-stack.sh <backup-dir>}
KEEP_DAYS=${KEEP_DAYS:-14}
STAMP=$(date -u +%Y%m%dT%H%M%SZ)
TARGET="$BACKUP_DIR/$STAMP"
COMPOSE="docker compose"

mkdir -p "$TARGET/store-blobs" "$TARGET/keys"

echo "==> PostgreSQL dump"
$COMPOSE exec -T postgres sh -lc 'pg_dump -U "$POSTGRES_USER" -d "$POSTGRES_DB"' \
    | gzip -9 > "$TARGET/store.sql.gz"
[ -s "$TARGET/store.sql.gz" ] || { echo "error: empty pg_dump output" >&2; exit 1; }

echo "==> MinIO store-blobs mirror"
# Credentials come from the running minio container itself, so this script
# never stores or duplicates them.
MC_USER=$($COMPOSE exec -T minio printenv MINIO_ROOT_USER)
MC_PASS=$($COMPOSE exec -T minio printenv MINIO_ROOT_PASSWORD)
$COMPOSE run --rm --no-deps -v "$TARGET/store-blobs:/backup" \
    --entrypoint sh minio-init -c "
    mc alias set src http://minio:9000 '$MC_USER' '$MC_PASS' &&
    mc mirror --overwrite src/store-blobs /backup"

echo "==> Key material"
$COMPOSE cp store:/var/lib/infinia-store/keys/. "$TARGET/keys/" 2>/dev/null \
    || echo "warn: could not copy store keys (is the app profile running?)" >&2

echo "==> Pruning backups older than $KEEP_DAYS days"
find "$BACKUP_DIR" -mindepth 1 -maxdepth 1 -type d -mtime "+$KEEP_DAYS" -exec rm -rf {} +

echo "==> OK: $TARGET"
du -sh "$TARGET"
