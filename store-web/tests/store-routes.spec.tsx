import { render, screen } from '@testing-library/react';
import { MemoryRouter, useLocation } from 'react-router';
import { expect, it, vi } from 'vitest';
import { RouterTree } from '../src/router';
vi.mock('../src/intro/app/page', () => ({ default: () => <h1>Introduction</h1> }));
vi.mock('../src/views/DiscoverView', () => ({ default: () => <h1>Store</h1> }));
vi.mock('../src/views/BrowseView', () => ({ default: () => <h1>Browse</h1> }));
function Location() { const location = useLocation(); return <output>{location.pathname + location.search + location.hash}</output>; }
it.each([['/', 'Introduction'], ['/store', 'Store'], ['/store/', 'Store'], ['/store/browse', 'Browse']])('renders %s', async (path, title) => {
  render(<MemoryRouter initialEntries={[path]}><RouterTree /></MemoryRouter>);
  expect(await screen.findByRole('heading', { name: title })).toBeInTheDocument();
});
it('preserves search and hash when forwarding old store links', async () => {
  render(<MemoryRouter initialEntries={['/browse?q=bee#results']}><RouterTree /><Location /></MemoryRouter>);
  await screen.findByRole('heading', { name: 'Browse' });
  expect(screen.getByRole('status')).toHaveTextContent('/store/browse?q=bee#results');
});
