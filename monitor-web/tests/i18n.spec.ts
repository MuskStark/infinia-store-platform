import { describe, expect, it } from 'vitest';
import en from '../src/locales/en';
import zhCN from '../src/locales/zh-CN';

/** Flat key paths for deep comparison (same parity contract as the store SPA). */
function keyPaths(messages: unknown, prefix = ''): string[] {
  if (typeof messages !== 'object' || messages === null) {
    return [prefix];
  }
  return Object.entries(messages).flatMap(([key, value]) =>
    keyPaths(value, prefix ? `${prefix}.${key}` : key),
  );
}

describe('locale structural parity', () => {
  it('zh-CN defines exactly the same keys as en', () => {
    const enKeys = keyPaths(en).sort();
    const zhKeys = keyPaths(zhCN).sort();
    expect(zhKeys).toEqual(enKeys);
  });

  it('covers every monitored component in both locales', () => {
    const components = [
      'api', 'web', 'auth', 'delivery', 'database', 'blob', 'scanner', 'upstream',
      'host-load', 'db-pool', 'http-quality', 'external',
    ];
    for (const key of components) {
      expect((en.status.component as Record<string, string>)[key]).toBeTruthy();
      expect((zhCN.status.component as Record<string, string>)[key]).toBeTruthy();
    }
  });

  it('covers the frozen-view banner copy', () => {
    expect(en.status.staleBanner).toContain('{time}');
    expect(zhCN.status.staleBanner).toContain('{time}');
    expect(en.status.neverReached).toBeTruthy();
    expect(zhCN.status.neverReached).toBeTruthy();
  });
});
