import { describe, expect, it, vi, beforeEach, afterEach } from 'vitest';
import { fireEvent, renderInRouter, resetStores, allButtons } from './helpers';
import { ApiRequestError } from '../src/api/client';
import SignInView from '../src/views/SignInView';
import en from '../src/locales/en';

/**
 * Invitation-only registration on the sign-in page (邀请注册): the public
 * registration policy decides whether the register tab shows a mandatory
 * invitation field; the submitted code is trimmed; server refusals surface as
 * localized form errors.
 */

const post = vi.fn();

vi.mock('../src/api/client', async (importOriginal) => ({
  ...(await importOriginal<typeof import('../src/api/client')>()),
  api: {
    getRegistrationPolicy: vi.fn(async () => ({ invitationRequired: false })),
    post: (path: string, body?: unknown) => post(path, body),
  },
  setAccessToken: vi.fn(),
}));

async function mountSignIn(policy = { invitationRequired: false }) {
  const { api } = await import('../src/api/client');
  vi.mocked(api.getRegistrationPolicy).mockResolvedValue(policy);
  renderInRouter(<SignInView />, { route: '/signin' });
  const registerTab = await vi.waitFor(() => {
    const tab = allButtons().find(
      (b) => b.getAttribute('role') === 'tab' && b.textContent === en.auth.register,
    );
    expect(tab).toBeTruthy();
    return tab!;
  });
  fireEvent.click(registerTab);
}

/** Fill the shared + register-only fields; the confirm input is the first
 * password-type input inside the expanded fieldset. */
function fillRegisterForm(invitation?: string) {
  const inputs = Array.from(document.querySelectorAll('input'));
  const email = inputs.find((i) => i.type === 'email')!;
  fireEvent.change(email, { target: { value: 'newbee@example.com' } });
  const passwords = inputs.filter((i) => i.type === 'password');
  fireEvent.change(passwords[0], { target: { value: 'Password123!' } });
  fireEvent.change(passwords[1], { target: { value: 'Password123!' } });
  if (invitation !== undefined) {
    const field = document.querySelector(
      '[data-testid="invitation-code-input"]',
    ) as HTMLInputElement;
    fireEvent.change(field, { target: { value: invitation } });
  }
}

beforeEach(() => {
  post.mockReset();
  post.mockRejectedValueOnce(new Error('network down')); // register
  post.mockRejectedValueOnce(new Error('network down')); // login
});
afterEach(() => {
  resetStores();
});

describe('Sign-in invitation field (邀请注册)', () => {
  it('omits the invitation field while registration is open', async () => {
    await mountSignIn();
    expect(
      document.querySelector('[data-testid="invitation-code-input"]'),
    ).toBeNull();
  });

  it('requires and submits a trimmed invitation code when the switch is on', async () => {
    await mountSignIn({ invitationRequired: true });
    const field = document.querySelector(
      '[data-testid="invitation-code-input"]',
    ) as HTMLInputElement;
    expect(field).toBeTruthy();

    fillRegisterForm('');
    const submit = allButtons().find((b) => b.type === 'submit')!;
    expect(submit.disabled).toBe(true);

    fillRegisterForm('  ABC123DEF456  ');
    fireEvent.submit(document.querySelector('form')!);
    await vi.waitFor(() => {
      expect(post).toHaveBeenCalledWith('/api/v1/auth/register', {
        email: 'newbee@example.com',
        password: 'Password123!',
        displayName: undefined,
        invitationCode: 'ABC123DEF456',
      });
    });
  });

  it('maps invitation refusals to localized form errors', async () => {
    post.mockReset();
    post.mockRejectedValueOnce(
      new ApiRequestError({
        code: 'invitation_invalid',
        detail: 'nope',
        status: 400,
      }),
    );
    post.mockRejectedValueOnce(new Error('network down'));
    await mountSignIn({ invitationRequired: true });
    fillRegisterForm('USEDUPCODE1');
    fireEvent.submit(document.querySelector('form')!);
    await vi.waitFor(() => {
      expect(document.body.textContent).toContain(en.errors.invitation_invalid);
    });
  });
});
