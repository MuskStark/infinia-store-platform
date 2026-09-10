-- Upstream sync state/log queries read the latest runs per source (admin
-- console sync status + log viewer); keep them off full-table sorts.
CREATE INDEX ix_sync_run_source ON sync_run (source_id, started_at DESC);
