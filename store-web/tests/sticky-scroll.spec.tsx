import { fireEvent, render, screen, waitFor } from '@testing-library/react';
import { afterEach, expect, it, vi } from 'vitest';
import { StickyScroll } from '../src/intro/components/ui/sticky-scroll-reveal';
afterEach(() => vi.restoreAllMocks());
it('shows the final illustration when the last step reaches the scroll region center', async () => {
  let offset = 0;
  vi.spyOn(HTMLElement.prototype, 'getBoundingClientRect').mockImplementation(function (this: HTMLElement) {
    const index = Number(this.querySelector('h3')?.textContent?.replace('Step ', '') ?? 0);
    const top = index * 500 - offset;
    return { top, bottom: top + 400, height: 400, left: 0, right: 300, width: 300, x: 0, y: top, toJSON() {} };
  });
  const content = Array.from({ length: 5 }, (_, i) => ({ title: `Step ${i}`, description: 'Description', content: <span>Art {i}</span> }));
  render(<StickyScroll content={content} />);
  expect(screen.getByRole('img')).toHaveTextContent('Art 0');
  const region = screen.getByRole('region');
  Object.defineProperty(region, 'getBoundingClientRect', { value: () => ({ top: 0, bottom: 600, height: 600, left: 0, right: 900, width: 900, x: 0, y: 0, toJSON() {} }) });
  Object.defineProperty(region, 'clientHeight', { value: 600 });
  offset = 1900;
  fireEvent.scroll(region);
  await waitFor(() => expect(screen.getByRole('img', { name: 'Step 4' })).toHaveTextContent('Art 4'));
  // Every step also retains an inline illustration for narrow screens.
  expect(screen.getAllByText('Art 4')).toHaveLength(2);
});
