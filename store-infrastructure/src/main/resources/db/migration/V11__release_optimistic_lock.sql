-- Optimistic locking for the release state machine (audit P1-4): the async scan
-- worker and human reviewers mutate the same row; a stale full-row save could
-- overwrite a reviewer's REJECT back to IN_REVIEW. The domain object carries the
-- loaded row_version and the repository rejects stale snapshots.
ALTER TABLE release ADD COLUMN row_version BIGINT NOT NULL DEFAULT 0;
