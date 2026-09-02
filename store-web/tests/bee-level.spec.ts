import { describe, expect, it } from 'vitest';
import { mount } from '@vue/test-utils';
import { createI18n } from 'vue-i18n';
import BeeLevelBadge from '../src/components/BeeLevelBadge.vue';
import en from '../src/locales/en';
import zhCN from '../src/locales/zh-CN';
import { BEE_MARKS, beeMark } from '../src/bee-levels';

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

  it('every level carries its own emblem and tone — the mark changes with the level', () => {
    const seenEmblems = new Set<string>();
    const seenTones = new Set<string>();
    for (const level of [0, 1, 2, 3, 4]) {
      const wrapper = mountBadge(level);
      const emblem = BEE_MARKS[level].emblem;
      expect(wrapper.text()).toContain(emblem);
      expect(wrapper.find('span').classes()).toContain(
        `magic-badge--${BEE_MARKS[level].tone}`,
      );
      seenEmblems.add(emblem);
      seenTones.add(BEE_MARKS[level].tone);
    }
    expect(seenEmblems.size).toBe(5);
    expect(seenTones.size).toBe(5);
  });

  it('the queen gets the royal gold badge', () => {
    const wrapper = mountBadge(4);
    expect(wrapper.text()).toContain('👑');
    expect(wrapper.find('span').classes()).toContain('magic-badge--gold');
  });

  it('demands mode prefixes the requirement with the target level emblem', () => {
    const wrapper = mountBadge(3, true);
    expect(wrapper.text()).toContain('Requires');
    expect(wrapper.text()).toContain('🛡️');
    expect(wrapper.text()).toContain('Guard');
    expect(wrapper.text()).toContain('Lv3+');
  });

  it('out-of-range levels clamp to the ladder ends', () => {
    expect(beeMark(-3).emblem).toBe(beeMark(0).emblem);
    expect(beeMark(99).emblem).toBe(beeMark(4).emblem);
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
