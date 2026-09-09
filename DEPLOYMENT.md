# Production Deployment

Single-host deployment of the Infinia Store Platform behind a dedicated
reverse proxy / WAF (tested topology: [SafeLine / 雷池](https://waf.chaitin.cn/)).
Everything the app needs ships in this repo: `docker-compose.yml` (app +
dependency planes), `Dockerfile` (store), `Dockerfile.monitor` (standalone
status monitor), `scripts/backup-stack.sh`.

## Topology

```
Internet ──HTTPS── SafeLine WAF (TLS termination, WAF/CC protection)
                       │ http://127.0.0.1:8080  → store   (infinia-store)
                       │ http://127.0.0.1:8090  → monitor (public status page)
                    docker compose stack
                       ├── postgres:17   (pgdata volume)
                       ├── minio         (miniodata volume, bucket store-blobs)
                       ├── redis:7       (reserved by design, not yet wired)
                       ├── store         (storedata volume: blobs fallback + JWT keys)
                       └── monitor       (monitordata volume: H2 mirror + history)
```

All compose ports are loopback-bound; both services are published only
through the WAF.

## First deployment

### Quick path

On a fresh Linux host with the repo cloned, `scripts/deploy.sh` automates
steps 1–2 below plus host preparation (Docker install from the Aliyun
docker-ce repo, apt source mirror rewrite, `registry-mirrors` in
`/etc/docker/daemon.json`, secret generation):

```sh
sudo scripts/deploy.sh --base-url https://store.example.com
# non-interactive / unattended:
sudo scripts/deploy.sh --yes --base-url https://store.example.com
```

It is idempotent (existing `.env` and mirror config are preserved) and prints
the remaining manual wiring (the SafeLine sites below). Steps 1–2 still apply
verbatim when you prefer to run them by hand.

1. **Environment file** (mandatory — compose refuses to run without it):

   ```sh
   cp .env.example .env
   # fill in: STORE_TICKET_SECRET / STORE_ROLLOUT_SECRET / STORE_CLI_CLIENT_SECRET
   #          (openssl rand -hex 32), POSTGRES_PASSWORD, MINIO_ROOT_PASSWORD,
   #          STORE_BASE_URL (public https:// URL), and database/MinIO creds
   ```

   The store boots under the `prod` Spring profile; `ProductionHardeningCheck`
   aborts startup if any dev-only secret survives, and `minio-init` propagates
   your MinIO credentials into the bucket setup and the store's S3 config.

2. **Start the stack**:

   ```sh
   docker compose --profile app up -d --build
   docker compose ps          # wait for store/monitor to report healthy
   ```

3. **SafeLine sites** — two sites, both with HTTP upstreams:

   | Site | Upstream | Notes |
   |---|---|---|
   | Store | `http://127.0.0.1:8080` (or the host LAN IP if SafeLine runs in its own container network) | Request body limit ≥ 1073741824 (1 GiB) — matches `store.max-upload-bytes`; SafeLine's default is far lower and large artifact uploads will fail with 413 |
   | Monitor (status page) | `http://127.0.0.1:8090` | No uploads, default body limit is fine; can use `GET /actuator/health` as the upstream health-check path |

   TLS terminates at SafeLine; certificates live there.

4. **Forwarded headers** (already configured, for awareness):
   both apps run `server.forward-headers-strategy: native`, so Tomcat honors
   `X-Forwarded-*` only when the peer is an internal proxy (default
   `internal-proxies` covers loopback + all private ranges — SafeLine's hop
   qualifies). Real client IPs reach logs, metrics and the per-IP rate limiter;
   spoofed `X-Forwarded-For` from outside is ignored. If SafeLine connects from
   a non-private address, extend `server.tomcat.remoteip.internal-proxies`.

5. **Monitor (8090)** — the status page and its API are anonymous **by design**
   (ADR-011: a status page that needs a login is useless during an outage).
   It is published through SafeLine like the store; the WAF's CC protection
   and rate limiting are its traffic defense, and the page stays reachable
   through the proxy even when the store itself is down. Set
   `MONITOR_TARGET_BASE_URL` to the store's **public** https URL so the
   monitor measures reachability through the WAF, not just LAN connectivity.

## First admin

Production boots with zero accounts by design — the demo credentials
(`admin@infinia.local` / `Password123!`) are public knowledge and seeding
refuses to run outside the local/dev/test profiles. Instead, the **first
account registered on an empty deployment automatically receives
PLATFORM_ADMIN**: open the site right after first boot, register your own
address, and you own the instance. Every later registration gets the plain
USER role; the bootstrap admin then assigns roles (PUBLISHER, REVIEWER,
PLATFORM_ADMIN), bee levels and status per account from the admin user
console (`/api/v1/admin/users`, wired into the Web admin view). Register
promptly after exposing the site — on an empty instance, whoever registers
first becomes admin.

## WAF on a separate host

When the reverse proxy / WAF runs on its own server, loopback bindings are
unreachable from it. Set in `.env`:

```sh
STORE_BIND_HOST=0.0.0.0      # or the store server's LAN IP only
MONITOR_BIND_HOST=0.0.0.0
docker compose --profile app up -d
```

Then point the WAF sites at `http://<store-server-LAN-IP>:8080` and
`:8090` (never 127.0.0.1 — that would hit the WAF host itself). Restrict
the opened ports to the WAF's IP via the DOCKER-USER chain — plain ufw
does NOT filter Docker-published ports:

```sh
iptables -I DOCKER-USER -p tcp --dport 8080 ! -s <waf-ip> -j DROP
iptables -I DOCKER-USER -p tcp --dport 8090 ! -s <waf-ip> -j DROP
# persist: apt install iptables-persistent && netfilter-persistent save
```

The forwarded-header trust needs no change: the WAF's hop still originates
from a private address, which the default internal-proxies covers.

## Backups

`scripts/backup-stack.sh <dir>` snapshots PostgreSQL (pg_dump), the MinIO
`store-blobs` bucket (mc mirror) and the store's JWT key files. Schedule it
from cron and copy the output off-host:

```cron
30 3 * * * root cd /opt/infinia-store && KEEP_DAYS=14 ./scripts/backup-stack.sh /mnt/backups/infinia
```

Restore:

```sh
gunzip -c <dir>/store.sql.gz | docker compose exec -T postgres psql
# blobs: from any mc host: mc alias set dst http://<host>:9000 ... ; mc mirror <dir>/store-blobs dst/store-blobs
# keys:  docker compose cp <dir>/keys store:/var/lib/infinia-store/keys/
```

The JWT keys are the deployment's secret store for access tokens — treat their
backups like passwords and keep an offline copy. Rotation = generate a new RSA
pair, swap the two files in the volume, restart the store (invalidates all
outstanding tokens).

## Upgrades

```sh
git pull && docker compose --profile app up -d --build
```

Flyway migrations (`store-infrastructure/src/main/resources/db/migration`) run
automatically on boot. Schema changes are additive per release; check
CHANGELOG.md before jumping major versions.

## Image pinning notes

- `postgres:17-alpine` / `redis:7-alpine`: major-pinned, as shipped.
- `minio/minio`: pinned to `RELEASE.2025-09-07T16-13-09Z-cpuv1` — the final
  official image (MinIO discontinued prebuilt images; `latest` is frozen).
  Verify on the host (`docker manifest inspect minio/minio:<tag>`) and
  re-evaluate against MinIO's source releases when adopting a newer server.
- CI builds and publishes `ghcr.io/<repo>` (store) and `ghcr.io/<repo>-monitor`
  on every push to `main`; pin by digest in `.env`-driven overrides if you
  prefer immutability over `--build`.

## Operational limits & knobs

- Resource ceilings per service: `DOCKER_CPUS_LIMIT` / `DOCKER_MEM_LIMIT`
  (defaults 2 CPUs / 1 GiB). The JVM sizes its heap from the container limit
  (`-XX:MaxRAMPercentage=75`).
- Container logs rotate at 10 MiB × 3 files (`json-file` driver) — set a log
  shipper on `/var/lib/docker/containers` if you need central aggregation.
- Alerting: `MONITOR_ALERT_WEBHOOK` (opt-in) receives state-change alerts from
  the monitor.
