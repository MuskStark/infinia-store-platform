import { defineStore } from 'pinia';
import { api, type Library } from '../api/client';

/** Library: favorites, entitlements and install history (design §7.4). */
export const useLibraryStore = defineStore('library', {
  state: () => ({
    library: null as Library | null,
    loading: false,
    error: null as string | null,
  }),
  actions: {
    async load() {
      this.loading = true;
      this.error = null;
      try {
        this.library = await api.get<Library>('/api/v1/me/library');
      } catch (e) {
        this.error = e instanceof Error ? e.message : 'error';
      } finally {
        this.loading = false;
      }
    },
  },
});
