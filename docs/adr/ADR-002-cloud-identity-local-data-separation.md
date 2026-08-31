# ADR-002: Cloud identity is separate from local data ownership

Status: Accepted (design §7.1, §18.2)

Store accounts (`store_user` + OAuth 2.1 tokens) never become the owner of local FengYu data.
The host keeps binding local rows to its existing principal; signing out or switching accounts
cannot hide or leak local chats, flows or plugin data. The first version ships no multi-profile
migration; a future project may add it explicitly.

Implementation notes: the SPA never receives the FengYu host's Store tokens. The host encrypts
access and refresh tokens in `cloud_account_binding` with machine-bound AES-GCM; deployments can
provide the encryption key from an OS keychain through `FENGYU_MACHINE_KEY`. The desktop OAuth
client requests `openid profile offline_access`, and the Store must allow those scopes plus the
authorization-code and refresh grants. The cross-repository contract test performs PKCE login,
calls `/api/v1/me`, refreshes the grant, then calls `/api/v1/me` again. The resource server validates
tokens against the same-app RSA key (no self-HTTP JWKS fetch at startup).

The only product credential UI is Store Web `/signin`. An unauthenticated authorization request is
saved in the Store HTTP session and redirected to `/signin?oauth=1`; Store Web obtains CSRF material
from `/oauth2/session-login/csrf` and submits a top-level form to `/oauth2/session-login`, allowing
Spring Security to resume the saved authorization request. The backend `/login` page is deprecated
and only redirects old bookmarks to `/signin`; it must never render Spring's generated login form.
