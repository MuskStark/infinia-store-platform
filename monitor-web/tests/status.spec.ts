import { describe, expect, it, vi, beforeEach } from 'vitest';
import { mount, flushPromises } from '@vue/test-utils';
import { createI18n } from 'vue-i18n';
import StatusView from '../src/views/StatusView.vue';
import en from '../src/locales/en';
import type { ServiceIncident, ServiceStatus } from '../src/api/client';

vi.mock('../src/api/client', () => ({
  api: {
    getStatus: vi.fn(),
    getIncidents: vi.fn(),
  },
}));

const { api } = (await import('../src/api/client')) as typeof import('../src/api/client');

const i18n = createI18n({
  legacy: false,
  locale: 'en',
  messages: { en },
});

function day(index: number): ServiceStatus['components'][number]['history'][number] {
  const date = new Date(Date.UTC(2026, 5, 1) + index * 86_400_000).toISOString().slice(0, 10);
  return { date, indicator: 'operational', uptimePercent: 100 };
}

/** The full 14-component merged page the monitor serves (13 mirrored + external). */
const componentKeys = [
  'api', 'web', 'auth', 'delivery', 'database', 'blob', 'scanner', 'upstream',
  'host-disk', 'host-memory', 'runtime-jvm', 'db-pool', 'http-quality', 'external',
];

function statusWith(overrides: Partial<ServiceStatus> = {}): ServiceStatus {
  return {
    indicator: 'operational',
    checkedAt: '2026-09-06T12:00:00Z',
    mirroredAt: '2026-09-06T11:59:30Z',
    stale: false,
    components: componentKeys.map((key, index) => ({
      key,
      indicator: index === 13 ? 'operational' : 'operational',
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

describe('StatusView (monitor)', () => {
  beforeEach(() => {
    vi.mocked(api.getStatus).mockResolvedValue(statusWith());
    vi.mocked(api.getIncidents).mockResolvedValue(incidents);
  });

  it('renders every component in the 19-slot hive with the overall center', async () => {
    const wrapper = mount(StatusView, { global: { plugins: [i18n] } });
    await flushPromises();

    expect(wrapper.find('[data-testid="status-banner"]').text().replace(/\s+/g, ' ')).toBe(
      '✓ All systems operational',
    );
    // 14 services + 1 overall + 4 spare = 19 hexagon cells.
    expect(wrapper.findAll('.hive-cell')).toHaveLength(19);
    expect(wrapper.findAll('[data-testid="status-component"]')).toHaveLength(14);
    expect(wrapper.findAll('.hive-cell--overall')).toHaveLength(1);
    // The legend mirrors the full component list, external reachability included.
    const legend = wrapper.find('[data-testid="hive-legend"]');
    expect(legend.findAll('li')).toHaveLength(14);
    expect(legend.text()).toContain('External reachability');
    expect(legend.text()).toContain('Host storage capacity');
    expect(legend.text()).toContain('HTTP response quality');
  });

  it('stays quiet when the mirror is fresh', async () => {
    const wrapper = mount(StatusView, { global: { plugins: [i18n] } });
    await flushPromises();
    expect(wrapper.find('[data-testid="stale-banner"]').exists()).toBe(false);
  });

  it('shows the frozen-view banner when the store is unreachable', async () => {
    vi.mocked(api.getStatus).mockResolvedValue(statusWith({
      indicator: 'major_outage',
      stale: true,
      components: componentKeys.map((key, index) => ({
        key,
        indicator: index === 13 ? 'major_outage' as const : 'operational' as const,
        uptime90d: 100,
        history: Array.from({ length: 90 }, (_, i) => day(i)),
      })),
    }));
    const wrapper = mount(StatusView, { global: { plugins: [i18n] } });
    await flushPromises();

    const banner = wrapper.find('[data-testid="stale-banner"]');
    expect(banner.exists()).toBe(true);
    expect(banner.text()).toContain('Store unreachable');
    // The overall banner follows the red external component, not the frozen greens.
    expect(wrapper.find('[data-testid="status-banner"]').text().replace(/\s+/g, ' ')).toBe(
      '✓ Major service outage',
    );
  });

  it('shows the never-reached copy when no snapshot exists at all', async () => {
    vi.mocked(api.getStatus).mockResolvedValue(statusWith({
      indicator: 'major_outage',
      mirroredAt: null,
      stale: true,
      components: [{
        key: 'external',
        indicator: 'major_outage',
        uptime90d: null,
        history: Array.from({ length: 90 }, (_, i) => day(i)),
      }],
    }));
    const wrapper = mount(StatusView, { global: { plugins: [i18n] } });
    await flushPromises();

    expect(wrapper.find('[data-testid="stale-banner"]').text()).toContain(
      'has not reached the store yet',
    );
    expect(wrapper.findAll('[data-testid="status-component"]')).toHaveLength(1);
  });

  it('renders monitor-owned incidents in the feed', async () => {
    const wrapper = mount(StatusView, { global: { plugins: [i18n] } });
    await flushPromises();
    const section = wrapper.find('[data-testid="status-incidents"]');
    expect(section.text()).toContain('External reachability is unavailable');
    expect(section.text()).toContain('Investigating');
  });

  it('recovers through the error state with retry', async () => {
    vi.mocked(api.getStatus).mockRejectedValueOnce(new Error('network down'));
    const wrapper = mount(StatusView, { global: { plugins: [i18n] } });
    await flushPromises();
    expect(wrapper.find('[role="alert"]').text()).toContain('network down');

    await wrapper.find('[role="alert"] button').trigger('click');
    await flushPromises();
    expect(wrapper.find('[data-testid="status-banner"]').exists()).toBe(true);
  });
});
