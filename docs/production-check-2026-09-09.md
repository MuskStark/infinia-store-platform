# Production check — 2026-09-09

Target: `https://store.summer.fan`, store host `jack@10.5.20.83`,
checkout `/home/jack/infinia-store-platform`, observed commit `60d3f67`.

## Applied live

- Backed up PostgreSQL before changing the schema:
  `/home/jack/infinia-store-platform/backups/store-before-name-fix-20260909T143827Z.sql`
  (about 2.5 MB, owner-only permissions).
- Widened `listing_i18n.name` from `VARCHAR(100)` to `TEXT`. Logs showed
  upstream imports failing with SQLSTATE 22001 on this column. The change
  used a transaction with a 3-second lock timeout and 15-second statement
  timeout. No application restart or data truncation was needed.
- Added the identical `V13__listing_name_text.sql` to both checkouts. Its SHA-256
  is `fd29cf77c6a7c6bb2a58cc037454b7b8b1d668e3d03975202917e4980f00bee5`.
  The live SQL was applied directly; Flyway will record V13 on a subsequent
  deployment containing this migration. Reapplication is supported.
- Changed PostgreSQL, MinIO and Redis restart policies from `no` to
  `unless-stopped`, using `docker update` and preserving the setting in both
  Compose files. Existing containers remained running and healthy.
  Original server Compose file: `backups/docker-compose.before-restart-policy.yml`.

## Verification

- A production PostgreSQL temporary table copied from `listing_i18n` accepted
  a 540-character name; the test transaction was rolled back.
- Local `ListingNameMigrationTest` passed: original limit rejects a long name,
  migration preserves existing data, full Unicode names round-trip, and the
  migration can be applied twice. `git diff --check` passed.
- Server Compose configuration validation passed. All three dependency
  containers reported `unless-stopped` and `healthy` after the update.
- Public homepage, catalog, deployed JS/CSS, store health and monitor health
  returned HTTP 200. Direct origin health returned `UP` in about 5 ms.
- Store container had no recorded restart and was not OOM-killed. Host disk,
  CPU and memory showed no resource exhaustion during this inspection.

## Remaining observations

- One initial public homepage request returned Cloudflare 522. Later checks
  succeeded. Its cause is not established; the WAF/Cloudflare origin connection
  logs were unavailable in the supplied store-host session. Do not attribute
  it to the name-column error or claim it is fixed.
- Status remained `degraded` solely for the upstream component; core service
  components were operational. Logs also contain upstream download 404s and
  payload security-scan rejections. These were not bypassed or relabeled.
- No manual upstream resync was triggered after the schema change. The
  application retries failed enabled upstreams hourly; a complete successful
  sync has not been verified.
- Local entity mapping and regression test accompany the migration. Changes
  have not been committed or pushed; no replacement application image was
  built or deployed.
