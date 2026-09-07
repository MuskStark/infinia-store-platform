-- Persist the adapter a sync resolved for each upstream item (audit P1-7): the
-- download path used to re-probe the source document and could disagree with the
-- sync-time decision (AUTO sources), making MCP-registry entries un-downloadable.
ALTER TABLE upstream_item ADD COLUMN adapter_type VARCHAR(40);
