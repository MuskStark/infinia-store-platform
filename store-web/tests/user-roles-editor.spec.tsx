import { fireEvent, render, screen, waitFor } from '@testing-library/react';
import { expect, it, vi } from 'vitest';
import UserRolesEditor from '../src/components/UserRolesEditor';
import { i18n } from '../src/i18n';

const label = (key: string) => String(i18n.t(key));
it('saves selected roles while retaining the required user role', async () => {
  const save = vi.fn().mockResolvedValue(undefined);
  render(<UserRolesEditor roles={['USER']} isSelf={false} onSave={save} />);
  fireEvent.click(screen.getByRole('button', { name: label('admin.editRoles') }));
  expect(screen.getByRole('checkbox', { name: label('role.USER') })).toBeDisabled();
  fireEvent.click(screen.getByRole('checkbox', { name: label('role.REVIEWER') }));
  fireEvent.click(screen.getByText(label('admin.saveRoles')));
  await waitFor(() => expect(save).toHaveBeenCalledWith(['USER', 'REVIEWER']));
  await waitFor(() => expect(screen.queryByRole('checkbox')).not.toBeInTheDocument());
});
it('discards cancelled changes and prevents self-demotion', () => {
  const save = vi.fn();
  render(<UserRolesEditor roles={['USER', 'PLATFORM_ADMIN']} isSelf onSave={save} />);
  fireEvent.click(screen.getByRole('button', { name: label('admin.editRoles') }));
  expect(screen.getByRole('checkbox', { name: label('role.PLATFORM_ADMIN') })).toBeDisabled();
  fireEvent.click(screen.getByRole('checkbox', { name: label('role.PUBLISHER') }));
  fireEvent.click(screen.getByText(label('common.cancel')));
  fireEvent.click(screen.getByRole('button', { name: label('admin.editRoles') }));
  expect(screen.getByRole('checkbox', { name: label('role.PUBLISHER') })).not.toBeChecked();
  expect(save).not.toHaveBeenCalled();
});
it('keeps the draft visible after a failed save', async () => {
  render(<UserRolesEditor roles={['USER']} isSelf={false} onSave={async () => { throw new Error('Save failed'); }} />);
  fireEvent.click(screen.getByRole('button', { name: label('admin.editRoles') }));
  fireEvent.click(screen.getByRole('checkbox', { name: label('role.REVIEWER') }));
  fireEvent.click(screen.getByText(label('admin.saveRoles')));
  expect(await screen.findByRole('alert')).toHaveTextContent('Save failed');
  expect(screen.getByRole('checkbox', { name: label('role.REVIEWER') })).toBeChecked();
});
it('opens a labelled modal and returns focus to its trigger on Escape', async () => {
  render(<UserRolesEditor roles={['USER']} isSelf={false} displayName="Test User" email="test@example.com" onSave={vi.fn()} />);
  const trigger = screen.getByRole('button', { name: label('admin.editRoles') });
  fireEvent.click(trigger);
  const dialog = screen.getByRole('dialog', { name: label('admin.editRoles') });
  expect(dialog).toHaveTextContent('test@example.com');
  expect(dialog.contains(document.activeElement)).toBe(true);
  fireEvent.keyDown(document.activeElement!, { key: 'Escape' });
  await waitFor(() => expect(screen.queryByRole('dialog')).not.toBeInTheDocument());
  expect(trigger).toHaveFocus();
});
