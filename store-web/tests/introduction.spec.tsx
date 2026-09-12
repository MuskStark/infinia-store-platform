import { act, render, screen } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { MemoryRouter, Route, Routes } from 'react-router';
import { afterEach, beforeEach, expect, it, vi } from 'vitest';
import SiteLink from '../src/intro/components/SiteLink';
import { useLocale } from '../src/intro/i18n';
import { i18n, setLocale } from '../src/i18n';
import { STORE_URL, BASE_PATH } from '../src/intro/site/config';

beforeEach(() => {
  const values = new Map<string, string>();
  vi.stubGlobal('localStorage', {
    getItem: (key: string) => values.get(key) ?? null,
    setItem: (key: string, value: string) => { values.set(key, value); },
  });
});
afterEach(async () => { await act(() => i18n.changeLanguage('en')); vi.unstubAllGlobals(); });

it('uses the same persisted language as the store in both directions', async () => {
  function LocaleControl() {
    const { locale, t, setLocale: change } = useLocale();
    return <button onClick={() => change(locale === 'en' ? 'zh-CN' : 'en')}>{t.nav.store}</button>;
  }
  render(<LocaleControl />);
  expect(screen.getByRole('button')).toHaveTextContent('Store');
  await userEvent.click(screen.getByRole('button'));
  expect(i18n.language).toBe('zh-CN');
  expect(localStorage.getItem('infinia.store.locale')).toBe('zh-CN');
  await act(() => setLocale('en'));
  expect(screen.getByRole('button')).toHaveTextContent('Store');
});

it('opens the store inside the same router and keeps external and section links native', async () => {
  render(<MemoryRouter initialEntries={['/']}><Routes>
    <Route path="/" element={<><SiteLink href={STORE_URL}>Store</SiteLink>
      <SiteLink href="#downloads">Downloads</SiteLink>
      <SiteLink href="https://example.com">Docs</SiteLink></>} />
    <Route path="/store" element={<h1>Store home</h1>} />
  </Routes></MemoryRouter>);
  expect(BASE_PATH).toBe('');
  expect(screen.getByText('Downloads')).toHaveAttribute('href', '#downloads');
  expect(screen.getByText('Docs')).toHaveAttribute('href', 'https://example.com');
  await userEvent.click(screen.getByText('Store'));
  expect(screen.getByRole('heading')).toHaveTextContent('Store home');
});

it('refreshes marquee copies on locale changes without accumulating StrictMode clones', async () => {
  const { StrictMode } = await import('react');
  const { InfiniteMovingCards } = await import('../src/intro/components/ui/infinite-moving-cards');
  const items = [{ name: 'plugin', title: 'Official', quote: 'English' }];
  const { container, rerender } = render(<StrictMode><InfiniteMovingCards items={items} /></StrictMode>);
  expect(container.querySelectorAll('li')).toHaveLength(2);
  rerender(<StrictMode><InfiniteMovingCards items={[{ ...items[0], quote: '中文' }]} /></StrictMode>);
  expect(container.querySelectorAll('li')).toHaveLength(2);
  expect(container).not.toHaveTextContent('English');
  expect(container.querySelector('[aria-hidden="true"][data-marquee-clone]')).toHaveTextContent('中文');
});
