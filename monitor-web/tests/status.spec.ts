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

describe('StatusView (monitor)', () => {
  beforeEach(() => {
    vi.mocked(api.getStatus).mockResolvedValue(statusWith());
    vi.mocked(api.getIncidents).mockResolvedValue(incidents);
  });


  it.each([
    ['operational', 'Operational', 'bg-emerald-500'],
    ['degraded', 'Degraded', 'bg-amber-400'],
    ['partial_outage', 'Partial outage', 'bg-orange-500'],
    ['major_outage', 'Major outage', 'bg-red-500'],
    ['no_data', 'No data', 'bg-slate-300'],
  ] as const)('shows the live component state %s independently of history', async (indicator, label, color) => {
    const page = statusWith();
    page.components[0].indicator = indicator;
    vi.mocked(api.getStatus).mockResolvedValue(page);
    const wrapper = mount(StatusView, { global: { plugins: [i18n] } });
    await flushPromises();
    const state = wrapper.find('[data-testid="component-live-status"]');
    expect(state.text()).toBe(label);
    expect(state.find('span').classes()).toContain(color);
    expect(wrapper.find('.hive-day').classes()).toContain('bg-emerald-500');
    wrapper.unmount();
  });

  it('renders no data neutrally without announcing healthy systems', async () => {
    vi.mocked(api.getStatus).mockResolvedValue(statusWith({
      indicator: 'no_data', mirroredAt: null, stale: true, components: [],
    }));
    const wrapper = mount(StatusView, { global: { plugins: [i18n] } });
    await flushPromises();
    expect(wrapper.find('[data-testid="status-banner"]').text()).toBe('No data');
    const overall = wrapper.find('.hive-overall');
    expect(overall.text()).toContain('No data');
    expect(overall.findAll('.text-success')).toHaveLength(0);
    expect(wrapper.text()).not.toContain('All systems operational');
    wrapper.unmount();
  });

  it('renders every component in the 19-slot hive with the overall center', async () => {
    const wrapper = mount(StatusView, { global: { plugins: [i18n] } });
    await flushPromises();

    expect(wrapper.find('[data-testid="status-banner"]').text().replace(/\s+/g, ' ')).toBe(
      'All systems operational',
    );
    // 12 services + 1 overall + 6 spare = 19 hexagon cells.
    expect(wrapper.findAll('.hive-cell')).toHaveLength(19);
    expect(wrapper.findAll('[data-testid="status-component"]')).toHaveLength(12);
    expect(wrapper.findAll('.hive-cell--overall')).toHaveLength(1);
    // The legend mirrors the full component list, external reachability included.
    const legend = wrapper.find('[data-testid="hive-legend"]');
    expect(legend.findAll('li')).toHaveLength(12);
    expect(legend.text()).toContain('External reachability');
    expect(legend.text()).toContain('Host server load');
    expect(legend.text()).toContain('HTTP response quality');
  });

  it('keeps the overall center on plain gray hexes with no status cells or ECG', async () => {
    const wrapper = mount(StatusView, { global: { plugins: [i18n] } });
    await flushPromises();

    const overall = wrapper.find('.hive-cell--overall');
    // Decorative gray lattice only — status days belong to the service combs.
    expect(overall.findAll('.hive-day')).toHaveLength(0);
    expect(overall.findAll('.hive-fill__hex').length).toBeGreaterThan(0);
    // The heartbeat trace is gone; the readouts stay.
    expect(wrapper.find('.ekg-beat__trace').exists()).toBe(false);
    expect(overall.text()).toContain('100.00%');
  });

  it('shows a day-cell detail tooltip on hover, with no comb-level tooltip', async () => {
    const wrapper = mount(StatusView, { global: { plugins: [i18n] } });
    await flushPromises();

    // Hovering one small day hex names the service, the day, its state and uptime.
    const dayButton = wrapper.findAll('[data-testid="status-component"]')[0].find('.hive-day');
    await dayButton.trigger('mouseenter');
    const tip = wrapper.find('[data-testid="day-tooltip"]');
    expect(tip.exists()).toBe(true);
    expect(tip.text()).toContain('Store API');
    expect(tip.text()).toContain('2026-06-01');
    expect(tip.text()).toContain('100.00%');
    await dayButton.trigger('mouseleave');
    expect(wrapper.find('[data-testid="day-tooltip"]').exists()).toBe(false);

    // The whole-comb rim carries no hover tooltip — only day cells do.
    const rim = wrapper.findAll('[data-testid="status-component"]')[0].find('.hive-cell__rim');
    expect(rim.attributes('mouseenter')).toBeUndefined();
    expect(wrapper.find('[data-testid="service-tooltip"]').exists()).toBe(false);
  });

  it('fits the complete hive without any scroll container', async () => {
    const wrapper = mount(StatusView, { global: { plugins: [i18n] } });
    await flushPromises();

    // No overflow container anywhere: the hive scales to the page width instead.
    expect(wrapper.find('.hive-scroll').exists()).toBe(false);
    const hive = wrapper.find('.hive');
    expect(hive.exists()).toBe(true);
    expect(hive.attributes('style')).toContain('transform');
    // jsdom reports zero client width, so the scale gracefully holds at 1 —
    // real browsers measure and shrink; both keep all 19 cells rendered.
    expect(wrapper.findAll('.hive-cell')).toHaveLength(19);
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
        indicator: 'operational' as const,
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
      'Major service outage',
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
