/**
 * Hand-written client for the monitor's public API. The store SPA generates
 * its client from the contract's OpenAPI; the monitor adds only two fields
 * (mirroredAt, stale) and one endpoint pair, so the shapes live here.
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
};

export type StatusComponent = {
  key: string;
  indicator: StatusIndicator;
  uptime90d?: number | null;
  history: StatusDayUptime[];
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
