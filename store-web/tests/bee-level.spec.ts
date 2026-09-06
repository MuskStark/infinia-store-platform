import { describe, expect, it } from 'vitest';
import { mount } from '@vue/test-utils';
import { createI18n } from 'vue-i18n';
import BeeLevelBadge from '../src/components/BeeLevelBadge.vue';
import en from '../src/locales/en';
import zhCN from '../src/locales/zh-CN';
import { BEE_MARKS, beeMark, type BeeTier } from '../src/bee-levels';

const i18n = (locale: 'en' | 'zh-CN') =>
  createI18n({ legacy: false, locale, messages: { en, 'zh-CN': zhCN } });

function mountBadge(level: number, demands = false) {
  return mount(BeeLevelBadge, {
    props: { level, demands },
    global: { plugins: [i18n('en')] },
  });
}

describe('BeeLevelBadge (蜜蜂等级标识随等级变更)', () => {
  it('renders the localized hive role and level number', () => {
    const wrapper = mount(BeeLevelBadge, {
      props: { level: 2 },
      global: { plugins: [i18n('en')] },
    });
    expect(wrapper.text()).toContain('Forager');
    expect(wrapper.text()).toContain('Lv2');
  });

  it('renders the Chinese ladder in zh-CN', () => {
    const wrapper = mount(BeeLevelBadge, {
      props: { level: 4 },
      global: { plugins: [i18n('zh-CN')] },
    });
    expect(wrapper.text()).toContain('蜂王');
    expect(wrapper.text()).toContain('Lv4');
  });

  it('every level carries its own honeycomb tier — the mark changes with the level', () => {
    const seenTiers = new Set<BeeTier>();
    for (const level of [0, 1, 2, 3, 4]) {
      const wrapper = mountBadge(level);
      const mark = BEE_MARKS[level];
      expect(wrapper.find('span').classes()).toContain(`bee-badge--${mark.tier}`);
      // Each level renders its own crest silhouette.
      const crest = wrapper.find('.bee-crest');
      expect(crest.exists()).toBe(true);
      expect(crest.find('svg').exists()).toBe(true);
      seenTiers.add(mark.tier);
    }
    expect(seenTiers.size).toBe(5);
  });

  it('the queen wears the crown crest filled with the brand sweep', () => {
    const wrapper = mountBadge(4);
    expect(wrapper.find('span').classes()).toContain('bee-badge--queen');
    expect(wrapper.html()).toContain('bee-royal-grad');
  });

  it('compact mode keeps the mark and level number only', () => {
    const wrapper = mount(BeeLevelBadge, {
      props: { level: 4, compact: true },
      global: { plugins: [i18n('en')] },
    });
    expect(wrapper.find('span').classes()).toContain('bee-badge--compact');
    expect(wrapper.text()).toContain('Lv4');
    expect(wrapper.text()).not.toContain('Queen');
  });

  it('demands mode prefixes the requirement with the target level', () => {
    const wrapper = mountBadge(3, true);
    expect(wrapper.text()).toContain('Requires');
    expect(wrapper.text()).toContain('Guard');
    expect(wrapper.text()).toContain('Lv3+');
  });

  it('out-of-range levels clamp to the ladder ends', () => {
    expect(beeMark(-3).tier).toBe(beeMark(0).tier);
    expect(beeMark(99).tier).toBe(beeMark(4).tier);
  });
});

describe('bee level locale keys', () => {
  it('defines the full five-step ladder 0..4 in both locales', () => {
    for (const level of [0, 1, 2, 3, 4]) {
      expect((en.beeLevel as Record<string, string>)[String(level)]).toBeTruthy();
      expect((zhCN.beeLevel as Record<string, string>)[String(level)]).toBeTruthy();
    }
  });

  it('brands the member level uniformly as "Infinia Level" in both locales', () => {
    expect(en.beeLevel.title).toBe('Infinia Level');
    expect(zhCN.beeLevel.title).toBe('Infinia Level');
    for (const locale of [en, zhCN]) {
      const values: string[] = [];
      (function walk(node: unknown) {
        if (typeof node === 'string') {
          values.push(node);
        } else if (typeof node === 'object' && node !== null) {
          Object.values(node).forEach(walk);
        }
      })(locale);
      for (const value of values) {
        expect(value).not.toMatch(/蜜蜂等级/i);
        expect(value).not.toMatch(/bee[- ]levels?/i);
      }
    }
  });

  it('defines admin user-management labels in both locales', () => {
    const enAdmin = en.admin as unknown as Record<string, unknown>;
    const zhAdmin = zhCN.admin as unknown as Record<string, unknown>;
    for (const key of ['users', 'usersHint', 'userSearch', 'userAccount', 'userStatus',
      'userLastLogin', 'setBeeLevel', 'disable', 'enable', 'minBeeLevel', 'beeLevelPublic']) {
      expect(typeof enAdmin[key]).toBe('string');
      expect(typeof zhAdmin[key]).toBe('string');
    }
  });
});
