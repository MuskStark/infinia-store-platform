/**
 * Live status feed over SSE (`GET /api/v1/status/events`). Owns the connection
 * state machine the page renders: live / reconnecting / disconnected /
 * unsupported.
 *
 * Reconnect policy: the browser's native EventSource retry is replaced with
 * our own exponential backoff (1s → 2s → … → 60s cap) so a monitor restart or
 * a network flap cannot hammer the stream. Because a manually recreated
 * EventSource does not resend the browser-managed Last-Event-ID header, the
 * last seen event id is tracked here and replayed via a `lastEventId` query
 * parameter — the server honours header or query alike, so reconnects replay
 * exactly the missed events instead of resending a full snapshot.
 *
 * A fatal HTTP failure (e.g. the 503 connection cap) closes the stream: the
 * page falls back to low-frequency polling and one upgrade attempt is
 * scheduled a minute later.
 */
import type { ServiceIncident, ServiceStatus, StatusComponent, StatusDayUptime, StatusIndicator } from './client';

export const STATUS_EVENTS_URL = '/api/v1/status/events';

export type Connection = 'live' | 'reconnecting' | 'disconnected' | 'unsupported';

export type StatusFeedEvent =
  | { type: 'snapshot'; eventId: number; status: ServiceStatus; incidents: ServiceIncident[] }
  | { type: 'component.updated'; component: StatusComponent; overall: StatusIndicator; checkedAt: string }
  | { type: 'incident.updated'; incident: ServiceIncident }
  | { type: 'history.updated'; component: string; uptime90d?: number | null; history: StatusDayUptime[] };

export type EventListener = (event: StatusFeedEvent) => void;
export type ConnectionListener = (connection: Connection) => void;

export interface StatusFeed {
  readonly supported: boolean;
  onEvent(listener: EventListener): () => void;
  onConnection(listener: ConnectionListener): () => void;
  close(): void;
}

const INITIAL_BACKOFF_MS = 1_000;
const MAX_BACKOFF_MS = 60_000;
/** After a fatal close (503 cap, proxy rejection): wait before one retry. */
const FATAL_RETRY_MS = 60_000;

export function openStatusFeed(): StatusFeed {
  if (typeof window === 'undefined' || typeof window.EventSource !== 'function') {
    return unsupportedFeed();
  }
  return new SseStatusFeed();
}

function unsupportedFeed(): StatusFeed {
  const listeners = new Set<EventListener>();
  const connectionListeners = new Set<ConnectionListener>();
  connectionListeners.forEach(listener => listener('unsupported'));
  return {
    supported: false,
    onEvent(listener) {
      listeners.add(listener);
      return () => listeners.delete(listener);
    },
    onConnection(listener) {
      listener('unsupported');
      connectionListeners.add(listener);
      return () => connectionListeners.delete(listener);
    },
    close() {
      listeners.clear();
      connectionListeners.clear();
    },
  };
}

class SseStatusFeed implements StatusFeed {
  readonly supported = true;
  private source: EventSource | null = null;
  private lastEventId = 0;
  private failures = 0;
  private reconnectTimer: ReturnType<typeof setTimeout> | null = null;
  private closed = false;
  private readonly eventListeners = new Set<EventListener>();
  private readonly connectionListeners = new Set<ConnectionListener>();
  private connection: Connection = 'reconnecting';

  constructor() {
    this.connect();
  }

  onEvent(listener: EventListener) {
    this.eventListeners.add(listener);
    return () => this.eventListeners.delete(listener);
  }

  onConnection(listener: ConnectionListener) {
    listener(this.connection);
    this.connectionListeners.add(listener);
    return () => this.connectionListeners.delete(listener);
  }

  close() {
    this.closed = true;
    this.clearTimer();
    this.source?.close();
    this.source = null;
    this.eventListeners.clear();
    this.connectionListeners.clear();
  }

  private connect() {
    if (this.closed) return;
    const url = this.lastEventId > 0
      ? `${STATUS_EVENTS_URL}?lastEventId=${this.lastEventId}`
      : STATUS_EVENTS_URL;
    const source = new EventSource(url);
    this.source = source;
    source.onopen = () => {
      this.failures = 0;
      this.setConnection('live');
    };
    source.onerror = () => {
      // CONNECTING = the browser wants to retry; take over with our backoff.
      // CLOSED = fatal (HTTP error / cap) — fall back to polling, retry later.
      const fatal = source.readyState === EventSource.CLOSED;
      source.close();
      if (this.source === source) this.source = null;
      if (this.closed) return;
      if (fatal) {
        this.setConnection('disconnected');
        this.schedule(FATAL_RETRY_MS);
        return;
      }
      this.setConnection('reconnecting');
      const backoff = Math.min(INITIAL_BACKOFF_MS * 2 ** this.failures, MAX_BACKOFF_MS);
      this.failures = Math.min(this.failures + 1, 6);
      this.schedule(backoff);
    };
    for (const name of ['snapshot', 'component.updated', 'incident.updated', 'history.updated'] as const) {
      source.addEventListener(name, (message: MessageEvent<string>) => {
        const parsed = parseEvent(name, message.data);
        if (parsed) {
          this.emit(parsed);
        }
      });
    }
  }

  private emit(event: StatusFeedEvent) {
    // The snapshot carries the server's event watermark; a later manual
    // reconnect replays from exactly there via the lastEventId query param.
    if (event.type === 'snapshot') {
      this.lastEventId = event.eventId;
    }
    this.eventListeners.forEach(listener => listener(event));
  }

  private schedule(delay: number) {
    this.clearTimer();
    this.reconnectTimer = setTimeout(() => this.connect(), delay);
  }

  private clearTimer() {
    if (this.reconnectTimer !== null) {
      clearTimeout(this.reconnectTimer);
      this.reconnectTimer = null;
    }
  }

  private setConnection(connection: Connection) {
    if (this.connection === connection) return;
    this.connection = connection;
    this.connectionListeners.forEach(listener => listener(connection));
  }
}

function parseEvent(name: string, data: string): StatusFeedEvent | null {
  let payload: unknown;
  try {
    payload = JSON.parse(data);
  } catch {
    return null;
  }
  if (typeof payload !== 'object' || payload === null) return null;
  const body = payload as Record<string, unknown>;
  switch (name) {
    case 'snapshot':
      if (!isStatus(body.status)) return null;
      return {
        type: 'snapshot',
        eventId: Number(body.eventId ?? 0),
        status: body.status,
        incidents: Array.isArray(body.incidents) ? body.incidents as ServiceIncident[] : [],
      };
    case 'component.updated':
      if (!isComponent(body.component) || typeof body.overall !== 'string') return null;
      return {
        type: 'component.updated',
        component: body.component,
        overall: body.overall as StatusIndicator,
        checkedAt: typeof body.checkedAt === 'string' ? body.checkedAt : '',
      };
    case 'incident.updated':
      if (typeof body.incidentId !== 'string') return null;
      return { type: 'incident.updated', incident: body as unknown as ServiceIncident };
    case 'history.updated':
      if (typeof body.component !== 'string' || !Array.isArray(body.history)) return null;
      return {
        type: 'history.updated',
        component: body.component,
        uptime90d: typeof body.uptime90d === 'number' ? body.uptime90d : null,
        history: body.history as StatusDayUptime[],
      };
    default:
      return null;
  }
}

function isStatus(value: unknown): value is ServiceStatus {
  return typeof value === 'object' && value !== null
    && Array.isArray((value as ServiceStatus).components);
}

function isComponent(value: unknown): value is StatusComponent {
  return typeof value === 'object' && value !== null
    && typeof (value as StatusComponent).key === 'string';
}
