# ADR-006: Ed25519 dual signing with revocable keys

Status: Accepted (design §8.3, §18.6)

`sha256` proves content integrity; the Ed25519 signature over the canonical envelope JSON
proves origin. Publishers may sign uploads with their own keys; the platform always adds its
signature at review approval (`PlatformSigningService`). Public keys live in `signing_key`
with `keyId`, validity window and status; private keys never enter the database — development
keeps them under `data/keys`, production must front them with KMS/HSM. Root rotation uses the
dual-signing transition described in §8.3.

Verified by `Ed25519SignerTest` and the signed-artifact assertions in the integration suite.
