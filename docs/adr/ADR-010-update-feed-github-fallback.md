# ADR-010: App update feed with staged GitHub fallback

Status: Accepted (design §8.4, §18.10)

`GET /api/v1/updates/app` serves the signed, `HMAC(installId)`-bucketed feed described in
§8.4 and stays `mandatory=false` — forced updates may never bypass local confirmation. The
design's staged migration (GitHub Releases as fallback while the feed aggregates both) applies
when this platform fronts the existing host release process; the feed response is
field-compatible with the host's `UpdateInfo`.

Verified by `UpdatesAndDeliveryTest` (stable/beta rollout, cohort stability, signature fields).
