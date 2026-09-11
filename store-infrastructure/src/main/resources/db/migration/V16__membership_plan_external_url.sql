-- Buy Me a Coffee (BMAC) support: each plan may point at a priced Extra on
-- the creator's BMC page (buymeacoffee.com/<user>/extras/<name>). The purchase
-- flow then redirects there instead of building a gateway cashier session, and
-- the webhook matches the payment back by supporter email + amount. Null keeps
-- the previous behavior (gateway-built checkout URL).
ALTER TABLE membership_plan ADD COLUMN external_url VARCHAR(512);
