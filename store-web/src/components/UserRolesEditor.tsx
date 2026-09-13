import { useState } from 'react';
import { Dialog } from 'radix-ui';
import { useTranslation } from 'react-i18next';

const ROLES = ['USER', 'PUBLISHER', 'ORG_ADMIN', 'REVIEWER', 'PLATFORM_ADMIN'];

export default function UserRolesEditor({ roles, isSelf, onSave, initiallyOpen = false, onCancel, displayName, email }: {
  displayName?: string;
  email?: string;
  initiallyOpen?: boolean;
  onCancel?: () => void;
  roles: string[];
  isSelf: boolean;
  onSave: (roles: string[]) => Promise<void>;
}) {
  const { t } = useTranslation();
  const [editing, setEditing] = useState(initiallyOpen);
  const [draft, setDraft] = useState<string[]>(Array.from(new Set([...roles, 'USER'])));
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const changed = draft.length !== roles.length || draft.some(role => !roles.includes(role));

  async function save() {
    setBusy(true);
    setError(null);
    try {
      await onSave(draft);
      setEditing(false);
    } catch (e) {
      setError(e instanceof Error ? e.message : String(e));
    } finally {
      setBusy(false);
    }
  }

  function close() {
    if (busy) return;
    setEditing(false);
    onCancel?.();
  }

  return <Dialog.Root open={editing} onOpenChange={open => {
    if (busy) return;
    if (open) {
      setDraft(Array.from(new Set([...roles, 'USER'])));
      setError(null);
      setEditing(true);
    } else close();
  }}>
    <Dialog.Trigger asChild>
      <button type="button" className="role-edit-trigger" aria-label={t('admin.editRoles')} title={t('admin.editRoles')}>
        <svg width="14" height="14" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="1.6" strokeLinecap="round" strokeLinejoin="round" aria-hidden="true">
          <path d="m15 5 4 4M4 20l4-1L20 7a2.8 2.8 0 0 0-4-4L4 15l-1 6Z" />
        </svg>
      </button>
    </Dialog.Trigger>
    <Dialog.Portal>
      <Dialog.Overlay className="role-dialog-overlay fixed inset-0 z-50 bg-black/35 backdrop-blur-sm" />
      <Dialog.Content className="role-dialog-content fixed left-1/2 top-1/2 z-50 flex max-h-[calc(100dvh-2rem)] w-[calc(100%-2rem)] max-w-lg -translate-x-1/2 -translate-y-1/2 flex-col overflow-hidden rounded-2xl border border-line bg-surface text-ink shadow-xl"
        onEscapeKeyDown={event => { if (busy) event.preventDefault(); }}
        onPointerDownOutside={event => { if (busy) event.preventDefault(); }}>
        <header className="flex items-start justify-between gap-4 px-6 pt-6 pb-2">
          <div>
            <Dialog.Title className="text-lg font-semibold">{t('admin.editRoles')}</Dialog.Title>
            <Dialog.Description className="mt-2 break-all text-sm text-muted">
              {displayName || email || t('admin.userRolesTitle')}{displayName && email ? ` · ${email}` : ''}
            </Dialog.Description>
          </div>
          <Dialog.Close asChild><button type="button" className="btn btn-ghost btn-sm !h-9 !w-9 !p-0 shrink-0" disabled={busy} aria-label={t('common.close')}><svg width="16" height="16" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="1.6" aria-hidden="true"><path d="m6 6 12 12M18 6 6 18" /></svg></button></Dialog.Close>
        </header>
        <div className="overflow-y-auto px-6 py-5" aria-busy={busy}>
          <fieldset disabled={busy} className="space-y-1">
            <legend className="sr-only">{t('admin.editRoles')}</legend>
            {ROLES.map(role => {
              const locked = role === 'USER' || (isSelf && role === 'PLATFORM_ADMIN');
              return <label key={role} className={`flex items-center gap-3 role-option rounded-xl border px-3 py-3 transition-colors ${draft.includes(role) ? 'border-brand/30 bg-brand/5' : 'border-transparent bg-surface hover:bg-surface-muted'} ${locked ? 'cursor-default' : 'cursor-pointer'}`}>
                <input type="checkbox" aria-label={t(`role.${role}`)} className="peer sr-only" checked={draft.includes(role)}
                  disabled={locked}
                  onChange={event => setDraft(current => event.target.checked ? [...current, role] : current.filter(value => value !== role))} />
                <span aria-hidden="true" className={`flex h-5 w-5 shrink-0 items-center justify-center rounded-md border ${draft.includes(role) ? 'border-brand bg-brand text-[#18181b]' : 'border-control/50 bg-surface'}`}>
                  {draft.includes(role) && <svg width="13" height="13" viewBox="0 0 16 16" fill="none" stroke="currentColor" strokeWidth="2"><path d="m3 8 3 3 7-7" /></svg>}
                </span>
                <span className="min-w-0">
                  <span className="block text-sm font-medium">{t(`role.${role}`)}</span>
                  <span className="mt-0.5 block text-xs leading-relaxed text-muted">{t(`admin.roleDescription.${role}`)}</span>
                </span>
                {locked && <span className="ml-auto whitespace-nowrap rounded-md bg-surface-muted px-2 py-1 text-[11px] text-muted">{t('admin.requiredRole')}</span>}
              </label>;
            })}
          </fieldset>
          <p className="mt-4 text-xs leading-relaxed text-muted">{t(isSelf ? 'admin.selfRolesHint' : 'admin.rolesHint')}</p>
          {error && <p role="alert" className="alert alert-error mt-4 text-sm">{error}</p>}
        </div>
        <footer className="flex justify-end gap-3 border-t border-line bg-surface-muted/40 px-6 py-4">
          <button type="button" className="btn btn-secondary min-w-24" disabled={busy} onClick={close}>{t('common.cancel')}</button>
          <button type="button" className="btn btn-primary min-w-28" disabled={busy || !changed} onClick={() => void save()}>{t(busy ? 'common.loading' : 'admin.saveRoles')}</button>
        </footer>
      </Dialog.Content>
    </Dialog.Portal>
  </Dialog.Root>;
}
