import { render, fireEvent, type RenderOptions, type RenderResult } from '@testing-library/react';

export { fireEvent };
import { MemoryRouter, Route, Routes } from 'react-router';
import type { ReactElement } from 'react';
import { useAuthStore } from '../src/stores/auth';
import type { PublicUser } from '../src/api/client';

/**
 * Shared helpers for the React Testing Library port of the @vue/test-utils
 * suite: the app's i18n singleton needs no provider (initReactI18next sets the
 * default instance), and route-bound views render inside a MemoryRouter that
 * mirrors the old createMemoryHistory mounts.
 */

export function renderInRouter(
  ui: ReactElement,
  {
    route = '/',
    path = '*',
    ...renderOptions
  }: { route?: string; path?: string } & RenderOptions = {},
): RenderResult {
  return render(
    <MemoryRouter initialEntries={[route]}>
      <Routes>
        <Route path={path} element={ui} />
      </Routes>
    </MemoryRouter>,
    renderOptions,
  );
}

export const DEMO_USER: PublicUser = {
  userId: 'u1',
  email: 'bee@example.com',
  displayName: 'Busy Bee',
  roles: ['USER', 'PUBLISHER'],
  beeLevel: 2,
  effectiveBeeLevel: 2,
  createdAt: '2026-08-01T00:00:00Z',
} as PublicUser;

/** Sign the store in before render (the pinia tests did auth.user = {...}). */
export function asUser(user: PublicUser = DEMO_USER) {
  useAuthStore.setState({ user, ready: true });
}

export function resetStores() {
  useAuthStore.setState({ user: null, ready: false });
}

/** The old wrapper.text() — everything currently in the document. */
export function bodyText(): string {
  return document.body.textContent ?? '';
}

export function allButtons(): HTMLButtonElement[] {
  return Array.from(document.querySelectorAll('button'));
}

export function buttonByText(text: string): HTMLButtonElement | undefined {
  return allButtons().find((b) => (b.textContent ?? '').trim() === text);
}
