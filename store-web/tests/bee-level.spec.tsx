import { describe, expect, it } from 'vitest';
import { render } from '@testing-library/react';
import BeeLevelBadge from '../src/components/BeeLevelBadge';
import en from '../src/locales/en';
import zhCN from '../src/locales/zh-CN';
import { i18n } from '../src/i18n';
import { BEE_MARKS, beeMark, type BeeTier } from '../src/bee-levels';

async function renderBadge(level: number, props: { demands?: boolean; compact?: boolean } = {}) {
  const utils = render(<BeeLevelBadge level={level} {...props} />);
  return utils;
}

describe('BeeLevelBadge (蜜蜂等级标识随等级变更)', () => {
  it('renders the localized hive role and level number', async () => {
    const { container } = await renderBadge(2);
    expect(container.textContent).toContain('Forager');
    expect(container.textContent).toContain('Lv2');
  });

  it('renders the Chinese ladder in zh-CN', async () => {
    await i18n.changeLanguage('zh-CN');
    try {
      const { container } = await renderBadge(4);
      expect(container.textContent).toContain('蜂王');
      expect(container.textContent).toContain('Lv4');
    } finally {
      await i18n.changeLanguage('en');
    }
  });

  it('every level carries its own honeycomb tier — the mark changes with the level', async () => {
    const seenTiers = new Set<BeeTier>();
    for (const level of [0, 1, 2, 3, 4]) {
      const { container, unmount } = await renderBadge(level);
      const mark = BEE_MARKS[level];
      const badge = container.querySelector('.bee-badge')!;
      expect(badge.className).toContain(`bee-badge--${mark.tier}`);
      // Each level renders its own crest silhouette.
      const crest = container.querySelector('.bee-crest');
      expect(crest).toBeTruthy();
      expect(crest!.querySelector('svg')).toBeTruthy();
      seenTiers.add(mark.tier);
      unmount();
    }
    expect(seenTiers.size).toBe(5);
  });

  it('the queen wears the crown crest filled with the brand sweep', async () => {
    const { container } = await renderBadge(4);
    expect(container.querySelector('.bee-badge')!.className).toContain('bee-badge--queen');
    expect(container.innerHTML).toContain('bee-royal-grad');
  });

  it('compact mode keeps the mark and level number only', async () => {
    const { container } = await renderBadge(4, { compact: true });
    expect(container.querySelector('.bee-badge')!.className).toContain('bee-badge--compact');
    expect(container.textContent).toContain('Lv4');
    expect(container.textContent).not.toContain('Queen');
  });

  it('demands mode prefixes the requirement with the target level', async () => {
    const { container } = await renderBadge(3, { demands: true });
    expect(container.textContent).toContain('Requires');
    expect(container.textContent).toContain('Guard');
    expect(container.textContent).toContain('Lv3+');
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
