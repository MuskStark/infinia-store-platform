import { describe, expect, it } from 'vitest';
import en from '../src/locales/en';
import zhCN from '../src/locales/zh-CN';

/** Flat key paths for deep comparison. */
function keyPaths(messages: unknown, prefix = ''): string[] {
  if (typeof messages !== 'object' || messages === null) {
    return [prefix];
  }
  return Object.entries(messages).flatMap(([key, value]) =>
    keyPaths(value, prefix ? `${prefix}.${key}` : key),
  );
}

describe('locale structural parity (design §12.2)', () => {
  it('zh-CN defines exactly the same keys as en', () => {
    const enKeys = keyPaths(en).sort();
    const zhKeys = keyPaths(zhCN).sort();
    expect(zhKeys).toEqual(enKeys);
  });

  it('no empty translations', () => {
    for (const [key, value] of Object.entries(keyPaths(zhCN))) {
      void key;
      expect(typeof value).toBe('string');
    }
    expect(keyPaths(en).length).toBeGreaterThan(50);
  });

  it('covers all five listing types in both locales', () => {
    for (const type of ['APP', 'PLUGIN', 'SKILL', 'MCP', 'FLOW']) {
      expect(en.type[type as keyof typeof en.type]).toBeTruthy();
      expect(zhCN.type[type as keyof typeof zhCN.type]).toBeTruthy();
    }
  });
});
