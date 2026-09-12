import { act } from 'react';
import { describe, expect, it } from 'vitest';
import { lazy } from 'react';
import { Link, Route, Routes, MemoryRouter, useNavigate } from 'react-router';
import { render, waitFor } from '@testing-library/react';
import RouteContent from '../src/components/RouteContent';
import en from '../src/locales/en';

/** Nav affordances inside the tree: MemoryRouter ignores prop-driven entries,
 *  so the recovery path is exercised the way users trigger it — by navigating. */
function NavButtons() {
  const navigate = useNavigate();
  return (
    <>
      <button onClick={() => navigate('/broken')}>go-broken</button>
      <Link to="/?recovered=1">recover</Link>
    </>
  );
}

describe('route content recovery', () => {
  it('shows an error when a route chunk fails, then recovers on navigation', async () => {
    const broken = Promise.reject(new Error('Failed to fetch dynamically imported module'));
    broken.catch(() => {});
    const LazyBroken = lazy(() => broken);

    const { container, getByText } = render(
      <MemoryRouter initialEntries={['/']}>
        <RouteContent>
          <Routes>
            <Route path="/" element={<p>Catalog content</p>} />
            <Route path="/broken" element={<LazyBroken />} />
          </Routes>
        </RouteContent>
        <NavButtons />
      </MemoryRouter>,
    );
    // Successful first paint: no error, content visible.
    expect(container.querySelector('[role="alert"]')).toBeNull();
    expect(container.textContent).toContain('Catalog content');

    // Navigate onto the broken chunk.
    act(() => {
      getByText('go-broken').click();
    });
    const alert = await waitFor(() => {
      const el = document.querySelector('[role="alert"]');
      expect(el).toBeTruthy();
      return el!;
    });
    expect(alert.textContent).toContain(en.common.pageLoadError);
    expect(alert.querySelector('button')?.textContent).toBe('Retry');

    // Navigating elsewhere re-keys the boundary and clears the failure.
    act(() => {
      getByText('recover').click();
    });
    await waitFor(() => {
      expect(document.querySelector('[role="alert"]')).toBeNull();
    });
    expect(container.textContent).toContain('Catalog content');
  });

  it('displays loading while the first route module is pending', async () => {
    let finish!: (value: { default: React.ComponentType }) => void;
    const pending = new Promise<{ default: React.ComponentType }>((resolve) => {
      finish = resolve;
    });
    const LazyPending = lazy(() => pending);

    const { container } = render(
      <MemoryRouter initialEntries={['/']}>
        <RouteContent>
          <Routes>
            <Route path="/" element={<LazyPending />} />
          </Routes>
        </RouteContent>
      </MemoryRouter>,
    );
    expect(document.querySelector('[role="status"]')?.textContent).toBe(en.common.loading);

    await act(async () => {
      finish({ default: () => <p>Loaded catalog</p> });
    });
    expect(container.querySelector('[role="status"]')).toBeNull();
    expect(container.textContent).toContain('Loaded catalog');
  });
});
