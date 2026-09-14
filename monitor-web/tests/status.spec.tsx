import { describe, expect, it, vi, beforeEach, afterEach } from 'vitest';
import { render, screen, fireEvent, waitFor, act } from '@testing-library/react';
import StatusView from '../src/views/StatusView';
import { daySlots } from '../src/hive';
import { api, type ServiceStatus, type ServiceIncident } from '../src/api/client';
import type { Connection, StatusFeedEvent } from '../src/api/events';
vi.mock('../src/api/client', () => ({api:{getStatus:vi.fn(),getIncidents:vi.fn()}}));

/** Controllable stand-in for the SSE feed: tests drive events/connection state. */
const feedState = vi.hoisted(() => ({
  connection: 'live' as Connection,
  eventListeners: new Set<(event: StatusFeedEvent) => void>(),
  connectionListeners: new Set<(connection: Connection) => void>(),
  supported: true,
}));
vi.mock('../src/api/events', () => ({
  openStatusFeed: vi.fn(() => ({
    supported: feedState.supported,
    onEvent(listener: (event: StatusFeedEvent) => void) {
      feedState.eventListeners.add(listener);
      return () => feedState.eventListeners.delete(listener);
    },
    onConnection(listener: (connection: Connection) => void) {
      listener(feedState.connection);
      feedState.connectionListeners.add(listener);
      return () => feedState.connectionListeners.delete(listener);
    },
    close() {},
  })),
}));

function day(index: number): ServiceStatus['components'][number]['history'][number] {
  const date = new Date(Date.UTC(2026, 5, 1) + index * 86_400_000).toISOString().slice(0, 10);
  return { date, indicator: 'operational', uptimePercent: 100 };
}

/** The full 14-component merged page the monitor serves (13 mirrored + external). */
const componentKeys = [
  'api', 'web', 'auth', 'delivery', 'database', 'blob', 'scanner', 'upstream',
  'host-load', 'db-pool', 'http-quality', 'external',
];

function statusWith(overrides: Partial<ServiceStatus> = {}): ServiceStatus {
  return {
    indicator: 'operational',
    checkedAt: '2026-09-06T12:00:00Z',
    mirroredAt: '2026-09-06T11:59:30Z',
    stale: false,
    components: componentKeys.map((key) => ({
      key,
      indicator: 'operational',
      uptime90d: 100,
      history: Array.from({ length: 90 }, (_, i) => day(i)),
    })),
    ...overrides,
  };
}

const incidents: ServiceIncident[] = [
  {
    incidentId: '0198c7a0-0000-7000-8000-000000000001',
    component: 'external',
    title: 'External reachability is unavailable',
    impact: 'outage',
    status: 'investigating',
    startedAt: '2026-09-06T11:30:00Z',
    resolvedAt: null,
    updatedAt: '2026-09-06T11:31:00Z',
  },
];

/** Pushes an SSE event into the mounted feed. */
function emit(event: StatusFeedEvent) {
  act(() => { feedState.eventListeners.forEach(listener => listener(event)); });
}

/** Pushes a connection change into the mounted feed. */
function connect(connection: Connection) {
  act(() => { feedState.connectionListeners.forEach(listener => listener(connection)); });
}

beforeEach(() => {
  vi.mocked(api.getStatus).mockReset().mockResolvedValue(statusWith());
  vi.mocked(api.getIncidents).mockReset().mockResolvedValue(incidents);
  feedState.connection = 'live';
  feedState.supported = true;
  feedState.eventListeners.clear();
  feedState.connectionListeners.clear();
});
afterEach(() => vi.useRealTimers());
it('renders all services with 90 daily cells and a neutral overall center', async () => {
  const {container}=render(<StatusView />);
  await screen.findByRole('tab', { name: 'Components' });
  expect(daySlots).toHaveLength(90);
  expect(container.querySelectorAll('.hive-day')).toHaveLength(componentKeys.length*90);
  expect(container.querySelectorAll('.comb')).toHaveLength(19);
  expect(container.querySelector('.hive-overall .hive-day')).toBeNull();
  expect(screen.getAllByTestId('component-live-status')).toHaveLength(componentKeys.length);
});
it.each(['degraded','major_outage','partial_outage','no_data'] as const)('keeps live %s separate from daily history', async indicator => {
 const page=statusWith(); page.components[0].indicator=indicator;
 vi.mocked(api.getStatus).mockResolvedValue(page);
 const {container}=render(<StatusView />);
 await screen.findByRole('tab', { name: 'Components' });
 expect(screen.getAllByTestId('component-live-status')[0].querySelector('i')).toHaveClass(indicator);
 expect(container.querySelector('.hive-day')).toHaveClass('operational');
});
it('does not announce healthy data during cold start', async () => {
  vi.mocked(api.getStatus).mockResolvedValue(statusWith({indicator:'no_data',components:[],stale:true,mirroredAt:null}));
  render(<StatusView />);
  expect(await screen.findByTestId('stale-banner')).toHaveTextContent('has not reached');
  expect(document.querySelector('.hive-overall')).toHaveTextContent('No data');
  expect(screen.queryByText('All systems operational')).toBeNull();
});
it('shows the mirror timestamp when stale', async () => {
  vi.mocked(api.getStatus).mockResolvedValue(statusWith({stale:true}));
  render(<StatusView />);
  expect(await screen.findByTestId('stale-banner')).toHaveTextContent('Store unreachable');
});
it('selects a service and exposes accessible day detail', async () => {
  const {container}=render(<StatusView />);
  await screen.findByRole('tab', { name: 'Components' });
  fireEvent.click(screen.getByRole('button',{name:/01 Store API/}));
  expect(container.querySelectorAll('.dimmed').length).toBeGreaterThan(0);
  const day=container.querySelector('.hive-day')!;
  fireEvent.focus(day);
  expect(container.querySelector('.hive-detail')).toHaveTextContent('2026-06-01');
  expect(day).toHaveAttribute('tabindex','0');
});
it('shows incidents through the animated tab', async () => {
  render(<StatusView />);
  await screen.findByRole('tab', { name: 'Components' });
  fireEvent.click(screen.getByRole('tab',{name:'Past Incidents'}));
  await screen.findByText('External reachability is unavailable');
});
it('does not call a failed incident feed an empty healthy feed', async () => {
  vi.mocked(api.getIncidents).mockRejectedValue(new Error('offline'));
  render(<StatusView />);
  await screen.findByRole('tab', { name: 'Components' });
  fireEvent.click(screen.getByRole('tab',{name:'Past Incidents'}));
  await screen.findByTestId('status-incidents');
  expect(screen.queryByText(/No incidents reported/)).toBeNull();
});
it('retries after a failed status request', async () => {
  vi.mocked(api.getStatus).mockRejectedValueOnce(new Error('offline'));
  render(<StatusView />);
  fireEvent.click(await screen.findByRole('button',{name:'Retry'}));
  expect(await screen.findByRole('tab', { name: 'Components' })).toBeInTheDocument();
});

describe('live feed', () => {
  it('shows the connection state in the heading', async () => {
    render(<StatusView />);
    expect(await screen.findByTestId('connection-state')).toHaveTextContent('Live');
    connect('reconnecting');
    expect(screen.getByTestId('connection-state')).toHaveTextContent('Reconnecting');
    connect('disconnected');
    expect(screen.getByTestId('connection-state')).toHaveTextContent('Connection lost');
  });
  it('a snapshot event replaces the whole page without refetching', async () => {
    render(<StatusView />);
    await screen.findByRole('tab', { name: 'Components' });
    const calls = vi.mocked(api.getStatus).mock.calls.length;
    const snapshot = statusWith({indicator:'major_outage'});
    emit({type:'snapshot',eventId:9,status:snapshot,incidents:[]});
    expect(document.querySelector('.hive-overall')).toHaveTextContent('Major outage');
    expect(screen.queryByText('External reachability is unavailable')).toBeNull();
    expect(vi.mocked(api.getStatus).mock.calls.length).toBe(calls);
  });
  it('a component event patches one comb and the overall indicator', async () => {
    const {container}=render(<StatusView />);
    await screen.findByRole('tab', { name: 'Components' });
    const external = statusWith().components.find(c=>c.key==='external')!;
    emit({type:'component.updated',component:{...external,indicator:'major_outage',pending:false},overall:'major_outage',checkedAt:'2026-09-06T12:00:05Z'});
    const row = screen.getAllByTestId('component-live-status').at(-1)!;
    expect(row.querySelector('i')).toHaveClass('major_outage');
    expect(container.querySelector('.hive-overall')).toHaveTextContent('Major outage');
    // Only the external comb re-rendered: the API cell keeps its color.
    const apiRow = screen.getAllByTestId('component-live-status')[0];
    expect(apiRow.querySelector('i')).toHaveClass('operational');
  });
  it('an incident event upserts into the feed', async () => {
    render(<StatusView />);
    await screen.findByRole('tab', { name: 'Components' });
    fireEvent.click(screen.getByRole('tab',{name:'Past Incidents'}));
    await screen.findByText('External reachability is unavailable');
    emit({type:'incident.updated',incident:{...incidents[0],status:'resolved',resolvedAt:'2026-09-06T11:45:00Z'}});
    expect(await screen.findByText('Resolved')).toBeInTheDocument();
  });
  it('marks a pending component as confirming without flipping its color', async () => {
    const {container}=render(<StatusView />);
    await screen.findByRole('tab', { name: 'Components' });
    const external = statusWith().components.find(c=>c.key==='external')!;
    emit({type:'component.updated',component:{...external,indicator:'operational',pending:true},overall:'operational',checkedAt:'2026-09-06T12:00:05Z'});
    const row = screen.getAllByTestId('component-live-status').at(-1)!;
    expect(row.querySelector('i')).toHaveClass('operational');
    expect(row.querySelector('.pending-flag')).toHaveTextContent('confirming');
    expect(container.querySelectorAll('.comb.pending').length).toBe(1);
  });
});

describe('polling fallback', () => {
  it('polls every 30s while the stream is unsupported and stops after unmount', async () => {
    feedState.supported = false;
    feedState.connection = 'unsupported';
    vi.useFakeTimers();
    const view=render(<StatusView />);
    await act(async()=>{await Promise.resolve();});
    expect(api.getStatus).toHaveBeenCalledTimes(1);
    await act(async()=>{vi.advanceTimersByTime(30000);});
    expect(api.getStatus).toHaveBeenCalledTimes(2);
    view.unmount();
    await act(async()=>{vi.advanceTimersByTime(120000);});
    expect(api.getStatus).toHaveBeenCalledTimes(2);
  });
  it('starts polling when the connection drops and stops when it returns', async () => {
    vi.useFakeTimers();
    render(<StatusView />);
    await act(async()=>{await Promise.resolve();});
    expect(api.getStatus).toHaveBeenCalledTimes(1);
    connect('disconnected');
    await act(async()=>{vi.advanceTimersByTime(30000);});
    expect(api.getStatus).toHaveBeenCalledTimes(2);
    connect('live');
    await act(async()=>{vi.advanceTimersByTime(120000);});
    expect(api.getStatus).toHaveBeenCalledTimes(2);
  });
  it('flags locally stale data after the validity window while disconnected', async () => {
    // Date must be faked too: "now" ticks from Date.now() inside the view.
    vi.useFakeTimers({ toFake: ['setTimeout','clearTimeout','setInterval','clearInterval','Date'] });
    render(<StatusView />);
    await act(async()=>{await Promise.resolve();});
    // The fallback poll fails too (monitor unreachable) — the last data ages out.
    vi.mocked(api.getStatus).mockRejectedValue(new Error('offline'));
    connect('disconnected');
    await act(async()=>{vi.advanceTimersByTime(181000);});
    expect(screen.getByTestId('local-stale-banner')).toHaveTextContent('Live connection lost');
  });
});
