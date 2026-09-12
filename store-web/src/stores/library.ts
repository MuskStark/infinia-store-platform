import { create } from 'zustand';
import { api, type Library } from '../api/client';

/** Library: favorites, entitlements and install history (design §7.4). */
interface LibraryState {
  library: Library | null;
  loading: boolean;
  error: string | null;
  load: () => Promise<void>;
}

export const useLibraryStore = create<LibraryState>((set) => ({
  library: null,
  loading: false,
  error: null,
  async load() {
    set({ loading: true, error: null });
    try {
      const library = await api.get<Library>('/api/v1/me/library');
      set({ library });
    } catch (e) {
      set({ error: e instanceof Error ? e.message : 'error' });
    } finally {
      set({ loading: false });
    }
  },
}));
