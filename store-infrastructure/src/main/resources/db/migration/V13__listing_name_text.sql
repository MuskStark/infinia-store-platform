-- Upstream catalogs can contain display names longer than 100 characters.
-- Preserve the complete name instead of failing an otherwise valid import.
-- Safe to reapply after the same widening was used as a production hotfix.
ALTER TABLE listing_i18n ALTER COLUMN name TYPE TEXT;
