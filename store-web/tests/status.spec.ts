import { describe, expect, it, vi, beforeEach } from 'vitest';
import { mount, flushPromises } from '@vue/test-utils';
import { createI18n } from 'vue-i18n';
import StatusView from '../src/views/StatusView.vue';
import en from '../src/locales/en';
import type { ServiceIncident, ServiceStatus } from '../src/api/client';

vi.mock('../src/api/client', () => ({
  api: {
    getServiceStatus: vi.fn(),
    getServiceIncidents: vi.fn(),
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

const status: ServiceStatus = {
  indicator: 'operational',
  checkedAt: '2026-09-04T03:00:00Z',
  components: [
    {
      key: 'database',
      indicator: 'operational',
      uptime90d: 100,
      history: Array.from({ length: 90 }, (_, i) => day(i)),
    },
    {
      key: 'upstream',
      indicator: 'degraded',
      uptime90d: 97.5,
      history: Array.from({ length: 90 }, (_, i) =>
        i === 0
          ? { date: day(i).date, indicator: 'degraded' as const, uptimePercent: 97.5 }
          : day(i),
      ),
    },
  ],
};

const incidents: ServiceIncident[] = [
  {
    incidentId: '0198c7a0-0000-7000-8000-000000000001',
    component: 'upstream',
    title: 'Upstream sync is degraded',
    impact: 'degraded',
    status: 'resolved',
    startedAt: '2026-09-03T08:00:00Z',
    resolvedAt: '2026-09-03T08:42:00Z',
    updatedAt: '2026-09-03T08:42:00Z',
  },
];

describe('StatusView', () => {
  beforeEach(() => {
    vi.mocked(api.getServiceStatus).mockResolvedValue(status);
    vi.mocked(api.getServiceIncidents).mockResolvedValue(incidents);
  });

  it('renders the overall banner, per-component bars and the incident feed', async () => {
    const wrapper = mount(StatusView, { global: { plugins: [i18n] } });
    await flushPromises();

    expect(wrapper.find('[data-testid="status-banner"]').text().replace(/\s+/g, ' ')).toBe(
      '✓ All systems operational',
    );
    const components = wrapper.findAll('[data-testid="status-component"]');
    expect(components).toHaveLength(2);
    // One uptime bar per history day, exactly 90 like the npm status page.
    expect(components[0].find('.flex.h-7').findAll('span')).toHaveLength(90);
    expect(components[0].text()).toContain('100.00% uptime');
    expect(components[1].text()).toContain('Degraded');

    const incidentSection = wrapper.find('[data-testid="status-incidents"]');
    expect(incidentSection.text()).toContain('Past Incidents');
    expect(incidentSection.text()).toContain('Upstream sync is degraded');
    expect(incidentSection.text()).toContain('Resolved');
  });

  it('shows the empty-incident state on a healthy history', async () => {
    vi.mocked(api.getServiceIncidents).mockResolvedValue([]);
    const wrapper = mount(StatusView, { global: { plugins: [i18n] } });
    await flushPromises();
    expect(wrapper.find('[data-testid="status-incidents"]').text()).toContain(
      'No incidents reported',
    );
  });

  it('recovers through the error state with retry', async () => {
    vi.mocked(api.getServiceStatus).mockRejectedValueOnce(new Error('network down'));
    const wrapper = mount(StatusView, { global: { plugins: [i18n] } });
    await flushPromises();
    expect(wrapper.find('[role="alert"]').text()).toContain('network down');

    await wrapper.find('[role="alert"] button').trigger('click');
    await flushPromises();
    expect(wrapper.find('[data-testid="status-banner"]').exists()).toBe(true);
  });
});
