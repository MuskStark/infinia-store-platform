# ADR-012: Configurable artifact storage — local filesystem or S3-compatible bucket

Status: Accepted

## Context

The artifact plane (design §5.1, §8.2) is a content-addressed blob store behind
the `BlobStorage` port, but only one implementation existed: `LocalFsBlobStorage`
under `store.blob-dir`. The design always named S3/MinIO as the production
shape — the compose stack even ships a MinIO container — yet nothing could use
it. A store deployment therefore coupled its artifact durability and capacity
to the host disk of a single machine: disk exhaustion kills uploads, downloads
and the blob probe at once, backups must copy a directory tree, and scaling or
migrating the store host means moving terabytes of blobs by hand.

Earlier options considered and rejected:

- **Only S3, drop the local backend** — the local-first development story
  (single jar, `local` profile, H2 under `~/.infinia-store`, no Docker for
  tests) would gain a hard infrastructure dependency for every contributor
  and every CI run.
- **Direct presigned-URL uploads from clients to object storage (design §9's
  end state)** — changes the publishing contract (upload-completion callbacks,
  client-side credential vending) and the scan pipeline's assumptions; it is
  the right next step but not a prerequisite for choosing where bytes live.
- **Storage-class abstraction inside the application module** — the port
  already lives in `store-domain` with infrastructure owning adapters; a new
  seam would duplicate it.

## Decision

1. **Artifact storage becomes a deployment choice behind the existing port**:
   `store.storage.type` selects `local` (default, unchanged semantics) or `s3`
   (any S3-compatible endpoint — MinIO, AWS S3, anything speaking SigV4).
   Settings live under `store.storage.s3.*` (`endpoint`, `region`, `bucket`,
   `access-key`, `secret-key`, `path-style-access`, `key-prefix`), all
   overridable via `STORE_STORAGE_*` environment variables. Blank credentials
   fall back to the SDK's default provider chain (env vars, profile, instance
   role) so real AWS deployments need no secrets in the store config.
2. **Blob keys stay content addresses (`sha256/<first2>/<rest>`) in both
   backends.** The database is therefore portable across a backend switch:
   only the bytes move (an operator re-syncs by re-uploading or copying the
   prefix), never the schema. `S3BlobStorage` returns the logical key and
   applies any `key-prefix` internally, so one bucket can be shared.
3. **Uploads stay streamed and validated by the store.** Because the digest is
   only known after the last byte, S3 uploads land on a `staging/<uuid>` object
   (multipart for anything over one 8 MiB part) and are promoted to the final
   key with a server-side copy — never downloaded again; hash mismatch or
   size-cap violation aborts the multipart upload and leaves nothing behind.
   Blobs that fit one part are PUT directly to their final key. `CopyObject`
   handles up to 5 GiB; larger blobs are promoted by part-copy (10 TiB
   ceiling).
4. **The status page follows the backend, not the directory.** The port gains
   `checkWritable()`; the `blob` component probe now exercises the real
   backend (local temp file, or a put+delete probe object against the bucket),
   so a misconfigured bucket paints the artifact-storage cell red instead of
   silently passing a local-disk check. The `host-load` disk probe anchors on
   the key directory when artifacts live remotely — that volume still carries
   keys, git exports and logs.
5. **Half-configured deployments fail at boot** (`store.storage.type=s3`
   without bucket, or without endpoint/region) rather than on the first
   upload. The compose stack grows a one-shot `minio-init` that creates
   `store-blobs`, so `docker compose up -d` yields a usable S3 target.

## Consequences

- The store keeps streaming uploads through the JVM (as it does today for
  local storage); presigned direct-to-bucket uploads remain future work on
  top of the same port, per design §9.
- `LocalFsBlobStorage` is no longer `@Component`-scanned; `BlobStorageConfig`
  wires exactly one backend bean, which also makes backend selection
  testable without booting the whole application.
- The status-page blob probe writes a small probe object to S3 deployments on
  every page load/sampling interval — negligible cost, and it is the only
  way the page can honestly claim artifact storage works.
- The local-filesystem `expectedSha256` check was corrected while porting
  (it compared the expected hash with itself); no caller passed a non-null
  hash before, so no behavior changed for existing deployments.
- Bucket lifecycle: operators should set an
  `abort-incomplete-multipart-upload` rule so abandoned uploads (process
  crash mid-put) age out; completed staging objects are deleted eagerly by
  the store.
