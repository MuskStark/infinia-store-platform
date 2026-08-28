-- Infinia Store Platform — platform-admin curation controls (design §12.4 管理):
-- listing-level listing/delisting (visibility) and editorial featuring.

ALTER TABLE listing ADD COLUMN featured BOOLEAN NOT NULL DEFAULT FALSE;
