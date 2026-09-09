/**
 * The /status handoff to the standalone monitor (ADR-011): the page that must
 * stay reachable when this app is not. The monitor's public address is runtime
 * config — STORE_MONITOR_PUBLIC_URL served from /api/v1/status/monitor — so
 * prebuilt images never need rebuilding per deployment; the build-time
 * VITE_MONITOR_BASE_URL stays as a fallback.
 */
import { api } from '../api/client';

const trimSlashes = (url: string) => url.replace(/\/+$/, '');

/** Runtime config first, build-time fallback second, null when unconfigured. */
export async function resolveMonitorUrl(): Promise<string | null> {
  try {
    const link = await api.getStatusMonitorLink();
    if (link.url) {
      return trimSlashes(link.url);
    }
  } catch {
    // The store is up enough to serve the SPA; a failed probe just falls
    // through to the build-time value / the honest unconfigured notice.
  }
  const baked = import.meta.env.VITE_MONITOR_BASE_URL as string | undefined;
  return baked ? trimSlashes(baked) : null;
}

export function redirectToMonitor(target: string): void {
  window.location.replace(target);
}
