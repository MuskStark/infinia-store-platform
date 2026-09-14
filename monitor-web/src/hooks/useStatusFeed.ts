import { useCallback, useEffect, useRef, useState } from 'react';
import { api, type ServiceIncident, type ServiceStatus } from '../api/client';
import { openStatusFeed, type Connection, type StatusFeed } from '../api/events';

/** Low-frequency polling when the live stream is unavailable (or fatal-closed). */
const FALLBACK_POLL_MS = 30_000;

export type FeedConnection = Connection | 'connecting';

export interface StatusFeedState {
  status: ServiceStatus | null;
  incidents: ServiceIncident[];
  error: string | null;
  feedError: boolean;
  loading: boolean;
  updated: number | null;
  connection: FeedConnection;
  refresh: () => void;
}

/**
 * The status page's data source: one REST load for the first paint, then the
 * SSE live feed patches individual components/incidents as confirmed changes
 * land. When the stream is unsupported or fatally closed, a 30 s polling
 * fallback keeps the page honest; manual refresh always re-runs the REST load
 * as the operator's escape hatch. A dropped connection keeps the last data
 * and reports the connection state — it never masquerades as live.
 */
export function useStatusFeed(): StatusFeedState {
  const [status, setStatus] = useState<ServiceStatus | null>(null);
  const [incidents, setIncidents] = useState<ServiceIncident[]>([]);
  const [error, setError] = useState<string | null>(null);
  const [feedError, setFeedError] = useState(false);
  const [loading, setLoading] = useState(true);
  const [updated, setUpdated] = useState<number | null>(null);
  const [connection, setConnection] = useState<FeedConnection>('connecting');
  const pending = useRef(false);
  const pollTimer = useRef<ReturnType<typeof setInterval> | null>(null);

  const load = useCallback(async () => {
    if (pending.current) return;
    pending.current = true;
    setLoading(true);
    const [page, feed] = await Promise.allSettled([api.getStatus(), api.getIncidents()]);
    if (page.status === 'fulfilled') {
      setStatus(page.value);
      setError(null);
      setUpdated(Date.now());
    } else {
      setError(page.reason instanceof Error ? page.reason.message : String(page.reason));
    }
    setFeedError(feed.status === 'rejected');
    if (feed.status === 'fulfilled') setIncidents(feed.value);
    setLoading(false);
    pending.current = false;
  }, []);

  const applyEvent = useCallback((event: Parameters<Parameters<StatusFeed['onEvent']>[0]>[0]) => {
    switch (event.type) {
      case 'snapshot':
        setStatus(event.status);
        setIncidents(event.incidents);
        setUpdated(Date.now());
        break;
      case 'component.updated':
        setStatus(current => current && {
          ...current,
          indicator: event.overall,
          checkedAt: event.checkedAt || current.checkedAt,
          components: current.components.some(component => component.key === event.component.key)
            ? current.components.map(component =>
                component.key === event.component.key ? event.component : component)
            : [...current.components, event.component],
        });
        setUpdated(Date.now());
        break;
      case 'incident.updated':
        setIncidents(current => {
          const merged = current.some(incident => incident.incidentId === event.incident.incidentId)
            ? current.map(incident => incident.incidentId === event.incident.incidentId
                ? event.incident : incident)
            : [event.incident, ...current];
          return [...merged].sort((a, b) => b.startedAt.localeCompare(a.startedAt));
        });
        setUpdated(Date.now());
        break;
      case 'history.updated':
        setStatus(current => current && {
          ...current,
          components: current.components.map(component =>
            component.key === event.component
              ? { ...component, uptime90d: event.uptime90d, history: event.history }
              : component),
        });
        break;
    }
  }, []);

  useEffect(() => {
    let active = true;
    void load();
    const feed = openStatusFeed();
    const startPolling = () => {
      if (pollTimer.current === null) {
        pollTimer.current = setInterval(() => void load(), FALLBACK_POLL_MS);
      }
    };
    const stopPolling = () => {
      if (pollTimer.current !== null) {
        clearInterval(pollTimer.current);
        pollTimer.current = null;
      }
    };
    const offConnection = feed.onConnection(state => {
      if (!active) return;
      setConnection(state);
      if (state === 'disconnected' || state === 'unsupported') {
        startPolling(); // low-frequency fallback while the stream is down
      } else {
        stopPolling();
      }
    });
    const offEvent = feed.onEvent(event => {
      if (active) applyEvent(event);
    });
    return () => {
      active = false;
      stopPolling();
      offConnection();
      offEvent();
      feed.close();
    };
  }, [load, applyEvent]);

  const refresh = useCallback(() => {
    void load();
  }, [load]);

  return { status, incidents, error, feedError, loading, updated, connection, refresh };
}
