-- Existing installations are baselined at version 0. New installations create their
-- schema through Hibernate in development; production should provision the base
-- schema before applying this incremental migration.
ALTER TABLE IF EXISTS market_orders ADD COLUMN IF NOT EXISTS season varchar(50);
ALTER TABLE IF EXISTS redemption_claims ADD COLUMN IF NOT EXISTS season varchar(50);
ALTER TABLE IF EXISTS redemption_claims ADD COLUMN IF NOT EXISTS credit_token_id varchar(80);
ALTER TABLE IF EXISTS fertilizer_batches ADD COLUMN IF NOT EXISTS original_volume_kg integer;

-- A historical order can be attributed only when its recorded token id maps to
-- exactly one issuance season. Do not guess from the farmer's latest issuance.
UPDATE market_orders o SET season = i.season
FROM (SELECT token_id, MIN(season) AS season FROM subsidy_credit_issuances
      GROUP BY token_id HAVING COUNT(DISTINCT season) = 1) i
WHERE o.credit_token_id = i.token_id AND o.season IS NULL;

UPDATE fertilizer_batches b SET original_volume_kg = b.volume_kg + COALESCE(d.distributed_kg, 0)
FROM (SELECT batch_id, SUM(amount_dispensed_kg) AS distributed_kg
      FROM distribution_logs GROUP BY batch_id) d
WHERE b.batch_id = d.batch_id AND b.original_volume_kg IS NULL;
UPDATE fertilizer_batches SET original_volume_kg = volume_kg WHERE original_volume_kg IS NULL;

CREATE INDEX IF NOT EXISTS ix_listing_marketplace ON product_listings(status, created_at DESC, listing_id DESC);
CREATE INDEX IF NOT EXISTS ix_listing_marketplace_filters ON product_listings(status, is_organic, is_subsidy_eligible, seller_id, created_at DESC);
CREATE INDEX IF NOT EXISTS ix_order_farmer_season_status ON market_orders(farmer_id, season, status);
CREATE INDEX IF NOT EXISTS ix_order_seller_season_status ON market_orders(seller_id, season, status);
CREATE INDEX IF NOT EXISTS ix_order_history_farmer ON market_orders(farmer_id, created_at DESC);
CREATE INDEX IF NOT EXISTS ix_order_history_seller ON market_orders(seller_id, created_at DESC);
CREATE INDEX IF NOT EXISTS ix_claim_seller_season_status ON redemption_claims(seller_id, season, status);
CREATE INDEX IF NOT EXISTS ix_claim_history ON redemption_claims(seller_id, submitted_at DESC);
CREATE INDEX IF NOT EXISTS ix_request_area_status ON fertilizer_requests(status, created_at DESC);
CREATE INDEX IF NOT EXISTS ix_distribution_request ON distribution_logs(request_id);
