import { describe, expect, it } from 'vitest';
import { render } from '@testing-library/react';
import { MemoryRouter } from 'react-router';
import ListingCard from '../src/components/ListingCard';
import StateChip from '../src/components/StateChip';
import type { CatalogItem } from '../src/api/client';

const item = {
  listingId: '00000000-0000-0000-0000-000000000001',
  coordinate: 'infinia://plugin/official/markdown',
  type: 'PLUGIN' as const,
  namespace: 'official',
  slug: 'markdown',
  name: 'Markdown Tools',
  summary: 'Render and convert Markdown',
  category: 'Productivity',
  tags: ['markdown'],
  iconUrl: undefined,
  latestVersion: '2.4.0',
  channel: 'stable' as const,
  downloads: 1234,
  publisherName: 'official',
  updatedAt: '2026-08-26T10:00:00Z',
} as CatalogItem;

describe('ListingCard', () => {
  it('renders name, localized type and version, linking to the detail page', () => {
    const { getByText, container } = render(
      <MemoryRouter>
        <ListingCard item={item} />
      </MemoryRouter>,
    );
    const link = container.querySelector('a');
    expect(link?.getAttribute('href')).toBe('/store/listing/official/markdown');
    expect(getByText('Markdown Tools')).toBeTruthy();
    expect(container.textContent).toContain('Plugin');
    expect(container.textContent).toContain('v2.4.0');
  });
});

describe('StateChip', () => {
  it('never encodes state by color alone — the label is always the localized status', () => {
    const { container, rerender } = render(<StateChip status="PUBLISHED" />);
    expect(container.textContent).toBe('Published');
    rerender(<StateChip status="QUARANTINED" />);
    expect(container.textContent).toBe('Quarantined');
  });

  it('falls back to the raw status for unknown backend states', () => {
    const { container } = render(<StateChip status="SOME_NEW_STATE" />);
    expect(container.textContent).toBe('SOME_NEW_STATE');
  });
});
