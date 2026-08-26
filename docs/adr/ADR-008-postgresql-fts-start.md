# ADR-008: Start with portable search; PostgreSQL FTS as the growth path

Status: Accepted (design §14.2, §18.8)

v1 catalog search filters in the repository adapter over slug, namespace, category, tags and
localized text — portable across PostgreSQL and H2 (tests run without Docker). When p95 or
volume targets from §14.1 are missed, move to PostgreSQL full-text search (tsvector/tsrank)
and then OpenSearch; the port interface (`ListingRepository.search`) isolates that change.
