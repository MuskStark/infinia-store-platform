import { fireEvent, render, screen, waitFor } from '@testing-library/react';
import { expect, it, vi } from 'vitest';
import AccountNotch from '../src/components/AccountNotch';
it('opens, supports keyboard navigation, and dispatches the selected action', async () => {
  const select = vi.fn();
  render(<AccountNotch label="Account" trigger="Bee" options={[{id:'profile',label:'Profile'},{id:'logout',label:'Logout'}]} onSelect={select} />);
  const trigger = screen.getByRole('button', {name:'Account'});
  fireEvent.click(trigger);
  await waitFor(() => expect(screen.getByRole('menuitem', {name:'Profile'})).toHaveFocus());
  fireEvent.keyDown(document.activeElement!, {key:'ArrowDown'});
  expect(screen.getByRole('menuitem', {name:'Logout'})).toHaveFocus();
  fireEvent.click(screen.getByRole('menuitem', {name:'Logout'}));
  expect(select).toHaveBeenCalledWith('logout');
  expect(trigger).toHaveAttribute('aria-expanded','false');
});
