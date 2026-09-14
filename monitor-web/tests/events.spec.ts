import { describe, expect, it, vi, beforeEach, afterEach } from 'vitest';
import { openStatusFeed, STATUS_EVENTS_URL, type Connection, type StatusFeedEvent } from '../src/api/events';

type Handler = (event: MessageEvent<string>) => void;

/** EventSource double: records urls, lets tests fire lifecycle + events. */
class FakeEventSource {
  static readonly CONNECTING = 0;
  static readonly OPEN = 1;
  static readonly CLOSED = 2;
  static instances: FakeEventSource[] = [];

  url: string;
  readyState = FakeEventSource.CONNECTING;
  onopen: (() => void) | null = null;
  onerror: (() => void) | null = null;
  private readonly handlers = new Map<string, Set<Handler>>();

  constructor(url: string) {
    this.url = url;
    FakeEventSource.instances.push(this);
  }

  addEventListener(name: string, handler: Handler) {
    if (!this.handlers.has(name)) this.handlers.set(name, new Set());
    this.handlers.get(name)!.add(handler);
  }

  close() {
    this.readyState = FakeEventSource.CLOSED;
  }

  // -- test controls ------------------------------------------------------
  simulateOpen() {
    this.readyState = FakeEventSource.OPEN;
    this.onopen?.();
  }

  simulateError() {
    this.onerror?.();
  }

  emit(name: string, data: unknown) {
    this.handlers.get(name)?.forEach(handler =>
      handler(new MessageEvent(name, { data: JSON.stringify(data) })));
  }
}

describe('openStatusFeed', () => {
  const realEventSource = window.EventSource;

  beforeEach(() => {
    FakeEventSource.instances = [];
    vi.stubGlobal('EventSource', FakeEventSource as unknown as typeof EventSource);
    vi.useFakeTimers();
  });

  afterEach(() => {
    vi.useRealTimers();
    vi.unstubAllGlobals();
    window.EventSource = realEventSource;
  });

  function collect(feed: ReturnType<typeof openStatusFeed>) {
    const events: StatusFeedEvent[] = [];
    const connections: Connection[] = [];
    feed.onEvent(event => events.push(event));
    feed.onConnection(connection => connections.push(connection));
    return { events, connections };
  }

  it('reports unsupported without a browser EventSource', () => {
    vi.stubGlobal('EventSource', undefined);
    const feed = openStatusFeed();
    expect(feed.supported).toBe(false);
    const { connections } = collect(feed);
    expect(connections).toEqual(['unsupported']);
  });

  it('connects, goes live on open, and parses the four event kinds', () => {
    const feed = openStatusFeed();
    const { events, connections } = collect(feed);
    const source = FakeEventSource.instances.at(-1)!;
    expect(source.url).toBe(STATUS_EVENTS_URL);

    source.simulateOpen();
    expect(connections).toContain('live');

    source.emit('snapshot', { eventId: 5, status: { indicator: 'operational', components: [], checkedAt: 'now' }, incidents: [] });
    source.emit('component.updated', { component: { key: 'api', indicator: 'degraded', history: [] }, overall: 'degraded', checkedAt: 'now' });
    source.emit('incident.updated', { incidentId: 'i-1', component: 'api', title: 't', impact: 'outage', status: 'investigating', startedAt: 'now', resolvedAt: null, updatedAt: 'now' });
    source.emit('history.updated', { component: 'api', uptime90d: 99.9, history: [] });
    source.emit('garbage', '{not json');
    expect(events.map(event => event.type)).toEqual([
      'snapshot', 'component.updated', 'incident.updated', 'history.updated',
    ]);
    expect(events[0]).toMatchObject({ type: 'snapshot', eventId: 5 });
    feed.close();
  });

  it('backs off exponentially and replays the last event id on reconnect', () => {
    const feed = openStatusFeed();
    const { connections } = collect(feed);
    const first = FakeEventSource.instances.at(-1)!;
    first.simulateOpen();
    first.emit('snapshot', { eventId: 5, status: { indicator: 'operational', components: [], checkedAt: 'now' }, incidents: [] });

    first.readyState = FakeEventSource.CONNECTING; // network drop, browser wants to retry
    first.simulateError();
    expect(connections).toContain('reconnecting');
    expect(FakeEventSource.instances).toHaveLength(1); // closed, not retried yet

    vi.advanceTimersByTime(1_000); // first backoff step
    const second = FakeEventSource.instances.at(-1)!;
    expect(FakeEventSource.instances).toHaveLength(2);
    expect(second.url).toContain('lastEventId=5');

    // A second consecutive failure (no successful open in between) doubles the wait.
    second.readyState = FakeEventSource.CONNECTING;
    second.simulateError();
    vi.advanceTimersByTime(1_000);
    expect(FakeEventSource.instances).toHaveLength(2);
    vi.advanceTimersByTime(1_000);
    expect(FakeEventSource.instances).toHaveLength(3);
    feed.close();
  });

  it('falls back to disconnected on a fatal error and retries once a minute later', () => {
    const feed = openStatusFeed();
    const { connections } = collect(feed);
    const first = FakeEventSource.instances.at(-1)!;
    first.readyState = FakeEventSource.CLOSED; // e.g. the 503 connection cap
    first.simulateError();
    expect(connections).toContain('disconnected');

    vi.advanceTimersByTime(59_000);
    expect(FakeEventSource.instances).toHaveLength(1);
    vi.advanceTimersByTime(2_000);
    expect(FakeEventSource.instances).toHaveLength(2);
    feed.close();
  });

  it('close stops reconnection entirely', () => {
    const feed = openStatusFeed();
    const first = FakeEventSource.instances.at(-1)!;
    first.readyState = FakeEventSource.CLOSED;
    first.simulateError();
    feed.close();
    vi.advanceTimersByTime(120_000);
    expect(FakeEventSource.instances).toHaveLength(1);
  });
});
