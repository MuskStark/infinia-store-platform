import { strFromU8, unzipSync } from 'fflate';

/**
 * Client-side .fyp plugin manifest reader. The publisher wizard uses it to
 * prefill the listing form from the package the user is about to publish, so
 * basic info is read from the plugin instead of typed by hand — and the
 * release version is taken from the manifest that the store scanner will
 * later compare against (plugin.version-mismatch), eliminating the class of
 * hand-typed mismatch the wizard used to invite.
 *
 * Mirrors the host contract (FengYu PluginManifest, schemaVersion 2): the
 * archive is a plain zip with manifest.json at the root. Only the manifest
 * entry is decompressed (fflate filter), so a large package costs nothing.
 */

export type PluginManifestImport = {
  id: string;
  name: string;
  description: string;
  version: string;
  category: string;
  /** engines.fengyu SemVer range, when declared. */
  engines: string | null;
};

const ID_PATTERN = /^[a-z0-9]+(?:[.-][a-z0-9]+)+$/;
const SEMVER_PATTERN = /^(0|[1-9]\d*)\.(0|[1-9]\d*)\.(0|[1-9]\d*)(?:-[0-9A-Za-z-]+(?:\.[0-9A-Za-z-]+)*)?(?:\+[0-9A-Za-z-]+(?:\.[0-9A-Za-z-]+)*)?$/;

export class PluginManifestError extends Error {}

/** Reads and validates a .fyp (zip) File; throws PluginManifestError with a reason. */
export async function readPluginManifest(file: File): Promise<PluginManifestImport> {
  if (!/\.fyp$/i.test(file.name) && !/\.zip$/i.test(file.name)) {
    throw new PluginManifestError('notAFyp');
  }
  let entries: Record<string, Uint8Array>;
  try {
    entries = unzipSync(new Uint8Array(await file.arrayBuffer()), {
      // Root manifest.json only; a directory entry of the same name would not
      // produce file content anyway.
      filter: (entry) => entry.name === 'manifest.json' && entry.size > 0,
    });
  } catch {
    throw new PluginManifestError('notAZip');
  }
  const manifestBytes = entries['manifest.json'];
  if (!manifestBytes) {
    throw new PluginManifestError('manifestMissing');
  }
  let json: Record<string, unknown>;
  try {
    json = JSON.parse(strFromU8(manifestBytes));
  } catch {
    throw new PluginManifestError('manifestInvalid');
  }
  if (json.schemaVersion !== 2) {
    throw new PluginManifestError('schemaVersion');
  }
  const id = typeof json.id === 'string' ? json.id : '';
  if (!ID_PATTERN.test(id)) {
    throw new PluginManifestError('idInvalid');
  }
  const name = typeof json.name === 'string' ? json.name.trim() : '';
  if (!name) {
    throw new PluginManifestError('nameMissing');
  }
  const version = typeof json.version === 'string' ? json.version : '';
  if (!SEMVER_PATTERN.test(version)) {
    throw new PluginManifestError('versionInvalid');
  }
  const engines = (json.engines as Record<string, unknown> | undefined)?.fengyu;
  return {
    id,
    name,
    description: typeof json.description === 'string' ? json.description : '',
    version,
    category: typeof json.category === 'string' ? json.category : '',
    engines: typeof engines === 'string' && engines.trim() ? engines.trim() : null,
  };
}

/**
 * Suggested listing slug from the plugin id: the last dot-separated segment
 * ("com.fengyu.priv.qrsync" → "qrsync", "official.markdown-tools" →
 * "markdown-tools" — dashes stay part of the name). Must match the listing
 * slug pattern [a-z0-9][a-z0-9-]{0,62}; falls back to the full id when it
 * already fits.
 */
export function suggestSlug(id: string): string {
  const last = id.split('.').pop() ?? id;
  const candidate = last.replace(/[^a-z0-9-]/g, '');
  if (/^[a-z0-9][a-z0-9-]{0,62}$/.test(candidate)) return candidate;
  return id.replace(/[^a-z0-9-]/g, '').slice(0, 63);
}
