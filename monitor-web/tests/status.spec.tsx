import { describe, expect, it, vi, beforeEach, afterEach } from 'vitest';
import { render, screen, fireEvent, waitFor, act } from '@testing-library/react';
import StatusView from '../src/views/StatusView';
import { daySlots } from '../src/hive';
import { api, type ServiceStatus, type ServiceIncident } from '../src/api/client';
vi.mock('../src/api/client', () => ({api:{getStatus:vi.fn(),getIncidents:vi.fn()}}));
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
    components: componentKeys.map((key, index) => ({
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


beforeEach(() => { vi.mocked(api.getStatus).mockReset().mockResolvedValue(statusWith()); vi.mocked(api.getIncidents).mockReset().mockResolvedValue(incidents); });
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
 expect(await screen.findByText('External reachability is unavailable')).toBeInTheDocument();
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
it('refreshes on cadence and stops after unmount', async () => {
 vi.useFakeTimers();
 const view=render(<StatusView />);
 await act(async()=>{await Promise.resolve();});
 await act(async()=>{vi.advanceTimersByTime(60000);});
 expect(api.getStatus).toHaveBeenCalledTimes(2);
 view.unmount();
 await act(async()=>{vi.advanceTimersByTime(60000);});
 expect(api.getStatus).toHaveBeenCalledTimes(2);
});
