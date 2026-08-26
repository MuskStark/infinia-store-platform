import { defineStore } from 'pinia';
import { api, type CatalogItem, type CatalogPage } from '../api/client';

export type ListingTypeFilter = 'APP' | 'PLUGIN' | 'SKILL' | 'MCP' | 'FLOW' | null;
export type SortKey = 'relevance' | 'recent' | 'downloads' | 'favorites';

/** Catalog browsing state (design §12.4 Discover/Browse). */
export const useCatalogStore = defineStore('catalog', {
  state: () => ({
    items: [] as CatalogItem[],
    nextCursor: null as string | null,
    loading: false,
    error: null as string | null,
    query: '',
    type: null as ListingTypeFilter,
    sort: 'relevance' as SortKey,
  }),
  actions: {
    async browse(reset = true) {
      this.loading = true;
      this.error = null;
      try {
        const params = new URLSearchParams();
        if (this.query.trim()) {
          params.set('query', this.query.trim());
        }
        if (this.type) {
          params.set('type', this.type);
        }
        params.set('sort', this.sort);
        params.set('limit', '24');
        if (!reset && this.nextCursor) {
          params.set('cursor', this.nextCursor);
        }
        const page = await api.get<CatalogPage>(`/api/v1/catalog?${params.toString()}`);
        this.items = reset ? (page.items ?? []) : [...this.items, ...(page.items ?? [])];
        this.nextCursor = page.nextCursor ?? null;
      } catch (e) {
        this.error = e instanceof Error ? e.message : 'error';
      } finally {
        this.loading = false;
      }
    },
  },
});
