import { create } from 'zustand';
import { api, type CatalogItem, type PublisherRelease } from '../api/client';

/** Publisher center state (design §8). */
interface PublisherState {
  listings: CatalogItem[];
  /** Polling cache keyed by releaseId (status refresh). */
  releases: Record<string, PublisherRelease>;
  /** All releases of a listing (incl. DRAFTs) keyed by listingId — the resume path. */
  releasesByListing: Record<string, PublisherRelease[]>;
  error: string | null;
  load: () => Promise<void>;
  refreshRelease: (releaseId: string) => Promise<void>;
  loadReleases: (listingId: string) => Promise<void>;
}

export const usePublisherStore = create<PublisherState>((set, get) => ({
  listings: [],
  releases: {},
  releasesByListing: {},
  error: null,
  async load() {
    set({ error: null });
    try {
      const listings = await api.get<CatalogItem[]>('/api/v1/publisher/listings');
      set({ listings });
    } catch (e) {
      set({ error: e instanceof Error ? e.message : 'error' });
    }
  },
  async refreshRelease(releaseId) {
    const release = await api.get<PublisherRelease>(
      `/api/v1/publisher/releases/${releaseId}`,
    );
    set({ releases: { ...get().releases, [releaseId]: release } });
  },
  async loadReleases(listingId) {
    const releases = await api.get<PublisherRelease[]>(
      `/api/v1/publisher/listings/${listingId}/releases`,
    );
    const nextReleases = { ...get().releases };
    for (const release of releases) {
      nextReleases[release.releaseId] = release;
    }
    set({
      releasesByListing: { ...get().releasesByListing, [listingId]: releases },
      releases: nextReleases,
    });
  },
}));
