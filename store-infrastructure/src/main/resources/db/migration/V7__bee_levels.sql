-- Bee ladder (蜜蜂等级) as the store's user-level identity: every account gets
-- a hive role (0=LARVA..4=QUEEN) and every listing may set the minimum level
-- required to view and download it. Defaults keep existing behavior: new users
-- are LARVA, all listings stay public.
ALTER TABLE store_user ADD COLUMN bee_level INT NOT NULL DEFAULT 0;
ALTER TABLE listing ADD COLUMN min_bee_level INT NOT NULL DEFAULT 0;
