# ADR-011: Standalone status monitor with pull-based mirroring

Status: Accepted

## Context

The public status page shipped inside `store-application` reports component
health from inside the store process. That is the right vantage point for
internal components (database latency, blob writability, pool saturation) but
structurally blind to the one event users most need to see: the store itself —
process, host, network or region — being gone. A page that dies with the thing
it monitors is not a status page during an outage. The deployment topology is
two servers: one runs the store, the other is dedicated to monitoring.

Earlier options considered and rejected:

- **Uptime Kuma / Prometheus+Alertmanager / Grafana on the monitoring host** —
  Grafana was ruled out by operator decision (the status page's own UI is the
  only display surface), and an alerting-only stack leaves no user-facing page
  at all during an outage.
- **Push model (monitor pushes probe results into the store's status API)** —
  keeps the page inside the store and therefore keeps the blind spot.
- **Static mirror of the SPA on the monitoring host** — duplicates the UI and
  drifts.

## Decision

1. **The status UI moves to a standalone Spring Boot application, `store-monitor`**,
   deployed on the monitoring host with its own embedded SPA (`monitor-web`,
   the hive status page migrated 1:1 in visual style). It serves the same
   shapes the store served (`/api/v1/status`, `/api/v1/status/incidents`) plus
   mirror metadata (`mirroredAt`, `stale`).
2. **Data flow is pull, not push.** The monitor polls the store's *public,
   anonymous* status API — which keeps working unchanged — and mirrors the last
   snapshot in memory and in a local H2 file database. The store gains no new
   endpoints, tokens or webhooks; it stays a purely passive data source.
3. **When the store is unreachable, the frozen snapshot keeps rendering**: the
   internal components hold their last-known indicators, the monitor's own
   `external` reachability component goes red from its own blackbox probes
   (homepage, `/actuator/health`, `/api/v1/status`, OIDC discovery — all derived
   from one configurable `monitor.target-base-url`), the overall banner turns
   red, a monitor-owned incident opens automatically, and once the mirror age
   crosses the stale window the page shows a "store unreachable — data as of …"
   banner. The store's own 90-day history ships inside every snapshot, so the
   monitor only persists buckets for what it observes itself.
4. **Alerting lives on the monitor** (the only component that survives the
   store): indicator transitions POST to an optional operator webhook
   (`monitor.alert-webhook`), throttled per component.
5. **The store's in-app page is retired**: `/status` in the store SPA redirects
   to the monitor's public address; the store's status API remains the data
   source and gained five comprehensive in-process probes (host disk, host
   memory via /proc/meminfo MemAvailable on Linux, JVM heap, Hikari pool,
   HTTP 5xx-ratio/p95) feeding 13 components.

## Consequences

- The page is reachable exactly when it matters most; during a store outage the
  monitor still tells the full story (frozen internals + live external + the
  outage incident), and the store's outage day is recorded honestly in the
  external component's own 90-day buckets.
- Two deployable jars on two hosts instead of one; symmetric build
  (`build-jar.sh` / `build-monitor-jar.sh`) and no shared state between them.
- One root config: re-pointing the monitor at a moved store is a single
  `MONITOR_TARGET_BASE_URL` plus a restart — no hot reload, deliberately.
- No quantitative metrics stack: the page stays qualitative (indicator ladder
  + daily uptime), matching the "single UI surface" decision. Adding
  Prometheus later remains possible without touching this design.
- The monitor itself is a single point; it serves no other function, and the
  store is unaffected if it dies. A third-party ping on the monitor is
  recommended (deployment doc).
