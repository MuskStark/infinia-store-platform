/**
 * Hand-written client for the monitor's public API. The store SPA generates
 * its client from the contract's OpenAPI; the monitor adds a few fields
 * (mirroredAt, stale, observation metadata) and the REST + SSE endpoint pair,
 * so the shapes live here.
 */

export type StatusIndicator =
  | 'operational'
  | 'degraded'
  | 'partial_outage'
  | 'major_outage'
  | 'no_data';

export type StatusDayUptime = {
  date: string;
  indicator: StatusIndicator;
  uptimePercent?: number | null;
  /** Share of the day covered by valid observations; interval-based days only. */
  coveragePercent?: number | null;
  /** True when the day comes from the legacy per-poll sampling statistics. */
  sampled?: boolean | null;
};

export type StatusComponent = {
  key: string;
  indicator: StatusIndicator;
  uptime90d?: number | null;
  history: StatusDayUptime[];
  /** When the observation feeding the indicator was made. */
  observedAt?: string | null;
  /** When the component was last observed operational; null if never. */
  lastSuccessAt?: string | null;
  /** True while a conflicting observation awaits confirmation (确认中). */
  pending?: boolean | null;
  /** True when observedAt is older than the observation validity window. */
  stale?: boolean | null;
};

export type ServiceStatus = {
  indicator: StatusIndicator;
  components: StatusComponent[];
  checkedAt: string;
  /** When the mirrored store snapshot was fetched; null if never reached. */
  mirroredAt?: string | null;
  /** True when the mirror is older than the stale window (or absent). */
  stale: boolean;
};

export type ServiceIncident = {
  incidentId: string;
  component: string;
  title: string;
  impact: 'outage' | 'degraded';
  status: 'investigating' | 'resolved';
  startedAt: string;
  resolvedAt?: string | null;
  updatedAt: string;
};

async function request<T>(path: string): Promise<T> {
  const response = await fetch(path, { headers: { Accept: 'application/json' } });
  if (!response.ok) {
    throw new Error(`${response.status} ${response.statusText}`);
  }
  return (await response.json()) as T;
}

export const api = {
  getStatus: (): Promise<ServiceStatus> => request<ServiceStatus>('/api/v1/status'),
  getIncidents: (): Promise<ServiceIncident[]> =>
    request<ServiceIncident[]>('/api/v1/status/incidents'),
};
