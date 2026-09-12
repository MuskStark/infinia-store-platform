import { create } from 'zustand';
import { api, type CatalogItem, type CatalogPage } from '../api/client';

export type ListingTypeFilter = 'APP' | 'PLUGIN' | 'SKILL' | 'MCP' | 'FLOW' | null;
export type SortKey = 'relevance' | 'recent' | 'downloads' | 'favorites';

/** Catalog browsing state (design §12.4 Discover/Browse). */
interface CatalogState {
  items: CatalogItem[];
  nextCursor: string | null;
  loading: boolean;
  error: string | null;
  query: string;
  type: ListingTypeFilter;
  sort: SortKey;
  browse: (reset?: boolean) => Promise<void>;
  set: (patch: Partial<Pick<CatalogState, 'query' | 'type' | 'sort'>>) => void;
}

export const useCatalogStore = create<CatalogState>((set, get) => ({
  items: [],
  nextCursor: null,
  loading: false,
  error: null,
  query: '',
  type: null,
  sort: 'relevance',
  async browse(reset = true) {
    const { query, type, sort, nextCursor, items } = get();
    set({ loading: true, error: null });
    try {
      const params = new URLSearchParams();
      if (query.trim()) {
        params.set('query', query.trim());
      }
      if (type) {
        params.set('type', type);
      }
      params.set('sort', sort);
      params.set('limit', '24');
      if (!reset && nextCursor) {
        params.set('cursor', nextCursor);
      }
      const page = await api.get<CatalogPage>(`/api/v1/catalog?${params.toString()}`);
      set({
        items: reset ? (page.items ?? []) : [...items, ...(page.items ?? [])],
        nextCursor: page.nextCursor ?? null,
      });
    } catch (e) {
      set({ error: e instanceof Error ? e.message : 'error' });
    } finally {
      set({ loading: false });
    }
  },
  set(patch) {
    set(patch);
  },
}));
