export type SessionLoginCsrf = {
  parameterName: string;
  token: string;
};

function hiddenInput(name: string, value: string): HTMLInputElement {
  const input = document.createElement('input');
  input.type = 'hidden';
  input.name = name;
  input.value = value;
  return input;
}

/** Establish the Authorization Server browser session and resume its saved request. */
export async function submitOAuthSessionLogin(email: string, password: string): Promise<void> {
  const response = await fetch('/oauth2/session-login/csrf', {
    credentials: 'same-origin',
    headers: { Accept: 'application/json' },
  });
  if (!response.ok) {
    throw new Error(`Cannot initialize OAuth sign-in (${response.status})`);
  }
  const csrf = (await response.json()) as SessionLoginCsrf;
  if (!csrf.parameterName || !csrf.token) {
    throw new Error('OAuth sign-in did not return CSRF protection');
  }

  // A real top-level form navigation is intentional: Spring Security redirects the
  // authenticated browser back to the saved /oauth2/authorize request, which then
  // redirects to FengYu's loopback callback. fetch() would consume that redirect.
  const form = document.createElement('form');
  form.method = 'post';
  form.action = '/oauth2/session-login';
  form.hidden = true;
  form.append(
    hiddenInput('username', email),
    hiddenInput('password', password),
    hiddenInput(csrf.parameterName, csrf.token),
  );
  document.body.append(form);
  form.submit();
}
