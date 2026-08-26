# ADR-002: Cloud identity is separate from local data ownership

Status: Accepted (design §7.1, §18.2)

Store accounts (`store_user` + OAuth 2.1 tokens) never become the owner of local FengYu data.
The host keeps binding local rows to its existing principal; signing out or switching accounts
cannot hide or leak local chats, flows or plugin data. The first version ships no multi-profile
migration; a future project may add it explicitly.

Implementation notes: the SPA access token lives in memory (sessionStorage bridge during
development only), refresh tokens would go to the OS keychain on the host, and the resource
server validates tokens against the same-app RSA key (no self-HTTP JWKS fetch at startup).
