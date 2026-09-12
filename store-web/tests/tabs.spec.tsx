import { useState } from 'react';
import { render, screen } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { expect, it } from 'vitest';
import Tabs from '../src/components/Tabs';

it('supports roving focus, wrapping arrow navigation and associated panels', async () => {
  function Example() {
    const [value, setValue] = useState('installed');
    return <Tabs value={value} onChange={setValue} label="Library" items={[
      { id: 'installed', label: 'Installed' }, { id: 'favorites', label: 'Favorites' },
    ]}><p>{value} content</p></Tabs>;
  }
  const user = userEvent.setup();
  render(<Example />);
  await user.tab();
  expect(screen.getByRole('tab', { name: 'Installed' })).toHaveFocus();
  await user.keyboard('{ArrowLeft}');
  const favorite = screen.getByRole('tab', { name: 'Favorites' });
  expect(favorite).toHaveFocus();
  expect(favorite).toHaveAttribute('aria-selected', 'true');
  expect(screen.getByRole('tabpanel')).toHaveAttribute('id', favorite.getAttribute('aria-controls'));
  expect(screen.getByRole('tabpanel')).toHaveTextContent('favorites content');
  await user.keyboard('{Home}');
  expect(screen.getByRole('tab', { name: 'Installed' })).toHaveFocus();
  await user.keyboard('{End}');
  expect(favorite).toHaveFocus();
});
