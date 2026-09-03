import { describe, expect, it } from 'vitest';
import { mount } from '@vue/test-utils';
import { createI18n } from 'vue-i18n';
import ListingCard from '../src/components/ListingCard.vue';
import StateChip from '../src/components/StateChip.vue';
import en from '../src/locales/en';

const i18n = createI18n({
  legacy: false,
  locale: 'en',
  messages: { en },
});

const item = {
  listingId: '00000000-0000-0000-0000-000000000001',
  coordinate: 'infinia://plugin/official/markdown',
  type: 'PLUGIN' as const,
  namespace: 'official',
  slug: 'markdown',
  name: 'Markdown Tools',
  summary: 'Render and convert Markdown',
  category: 'Productivity',
  tags: ['markdown'],
  iconUrl: undefined,
  latestVersion: '2.4.0',
  channel: 'stable' as const,
  downloads: 1234,
  publisherName: 'official',
  updatedAt: '2026-08-26T10:00:00Z',
};

describe('ListingCard', () => {
  it('renders name, localized type and version, linking to the detail page', () => {
    const wrapper = mount(ListingCard, {
      props: { item },
      global: {
        plugins: [i18n],
        stubs: {
          RouterLink: {
            name: 'RouterLink',
            props: ['to'],
            template: '<a :href="to"><slot /></a>',
          },
        },
      },
    });
    const link = wrapper.findComponent({ name: 'RouterLink' });
    expect(link.props('to')).toBe('/listing/official/markdown');
    expect(wrapper.text()).toContain('Markdown Tools');
    expect(wrapper.text()).toContain('Plugin');
    expect(wrapper.text()).toContain('v2.4.0');
  });
});

describe('StateChip', () => {
  it('never encodes state by color alone — the label is always the localized status', async () => {
    const wrapper = mount(StateChip, { props: { status: 'PUBLISHED' }, global: { plugins: [i18n] } });
    expect(wrapper.text()).toBe('Published');
    await wrapper.setProps({ status: 'QUARANTINED' });
    expect(wrapper.text()).toBe('Quarantined');
  });

  it('falls back to the raw status for unknown backend states', () => {
    const wrapper = mount(StateChip, { props: { status: 'SOME_NEW_STATE' }, global: { plugins: [i18n] } });
    expect(wrapper.text()).toBe('SOME_NEW_STATE');
  });
});
