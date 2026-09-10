// @vitest-environment node
// fflate (node_modules) and the jsdom sandbox hold typed arrays from different
// realms — identity checks inside the zip codec fail there. The reader is
// realm-agnostic pure JS; Node's native File covers the whole chain.
import { describe, expect, it } from 'vitest';
import { strFromU8, zipSync } from 'fflate';
import {
  PluginManifestError,
  readPluginManifest,
  suggestSlug,
} from '../src/utils/pluginManifest';

/** Host-contract plugin manifest (FengYu PluginManifest, schemaVersion 2). */
const VALID_MANIFEST = {
  schemaVersion: 2,
  id: 'com.fengyu.priv.qrsync',
  name: 'FY-QRSync Offline Transfer',
  description: 'Move files across air-gapped machines by streaming QR-code frames.',
  version: '2.1.0',
  category: 'transfer',
  ui: { entry: 'index.js' },
  permissions: ['files.read'],
  engines: { fengyu: '>=4.0.0 <5.0.0' },
};

function fyp(manifest: unknown, extra: Record<string, string> = {}): File {
  const entries: Record<string, Uint8Array> = {
    'manifest.json': strToU8(JSON.stringify(manifest)),
    'index.js': strToU8('export function run() { return 1; }'),
    ...Object.fromEntries(
      Object.entries(extra).map(([name, content]) => [name, strToU8(content)]),
    ),
  };
  return new File([zipSync(entries) as BlobPart], 'plugin.fyp');
}

function strToU8(value: string): Uint8Array {
  return new TextEncoder().encode(value);
}

describe('plugin manifest import (publisher wizard autofill)', () => {
  it('reads the manifest from a .fyp package', async () => {
    const manifest = await readPluginManifest(fyp(VALID_MANIFEST));
    expect(manifest.id).toBe('com.fengyu.priv.qrsync');
    expect(manifest.name).toBe('FY-QRSync Offline Transfer');
    expect(manifest.version).toBe('2.1.0');
    expect(manifest.category).toBe('transfer');
    expect(manifest.engines).toBe('>=4.0.0 <5.0.0');
    expect(manifest.description).toContain('air-gapped');
  });

  it('suggests the listing slug from the last id segment', () => {
    expect(suggestSlug('com.fengyu.priv.qrsync')).toBe('qrsync');
    expect(suggestSlug('official.markdown-tools')).toBe('markdown-tools');
    expect(suggestSlug('a.b.c-way-x')).toBe('c-way-x');
  });

  it('rejects non-plugin files with distinct reasons', async () => {
    await expect(readPluginManifest(new File([new Uint8Array([1])], 'a.txt')))
      .rejects.toThrow(PluginManifestError);
    await expect(readPluginManifest(new File([new Uint8Array([1, 2, 3])], 'a.fyp')))
      .rejects.toThrow(PluginManifestError);
    const noManifest = new File([
      zipSync({ 'index.js': strToU8('x') }) as BlobPart,
    ], 'a.fyp');
    await expect(readPluginManifest(noManifest)).rejects.toThrow(PluginManifestError);
  });

  it('enforces the host contract on schemaVersion, id, name and version', async () => {
    await expect(readPluginManifest(fyp({ ...VALID_MANIFEST, schemaVersion: 1 })))
      .rejects.toThrow(PluginManifestError);
    await expect(readPluginManifest(fyp({ ...VALID_MANIFEST, id: 'Not A Slug' })))
      .rejects.toThrow(PluginManifestError);
    await expect(readPluginManifest(fyp({ ...VALID_MANIFEST, name: '  ' })))
      .rejects.toThrow(PluginManifestError);
    await expect(readPluginManifest(fyp({ ...VALID_MANIFEST, version: '2.1' })))
      .rejects.toThrow(PluginManifestError);
  });

  it('treats a missing engines block as null, not an error', async () => {
    const { engines, ...withoutEngines } = VALID_MANIFEST;
    void engines;
    const manifest = await readPluginManifest(fyp(withoutEngines));
    expect(manifest.engines).toBeNull();
    expect(manifest.version).toBe('2.1.0');
  });
});
