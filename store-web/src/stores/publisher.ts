import { defineStore } from 'pinia';
import { api, type CatalogItem, type PublisherRelease } from '../api/client';

/** Publisher center state (design §8). */
export const usePublisherStore = defineStore('publisher', {
  state: () => ({
    listings: [] as CatalogItem[],
    releases: {} as Record<string, PublisherRelease>,
    error: null as string | null,
  }),
  actions: {
    async load() {
      this.error = null;
      try {
        this.listings = await api.get<CatalogItem[]>('/api/v1/publisher/listings');
      } catch (e) {
        this.error = e instanceof Error ? e.message : 'error';
      }
    },
    async refreshRelease(releaseId: string) {
      this.releases[releaseId] = await api.get<PublisherRelease>(
        `/api/v1/publisher/releases/${releaseId}`,
      );
    },
  },
});
