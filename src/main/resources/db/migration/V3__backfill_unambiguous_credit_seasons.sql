-- Pending legacy orders never recorded a transfer token. Attribute them only
-- when the farmer had exactly one issuance season; otherwise leave them for review.
UPDATE market_orders o SET season = i.season, credit_token_id = i.token_id
FROM (SELECT farmer_id, MIN(season) AS season, MIN(token_id) AS token_id
      FROM subsidy_credit_issuances GROUP BY farmer_id
      HAVING COUNT(DISTINCT season) = 1 AND COUNT(DISTINCT token_id) = 1) i
WHERE o.farmer_id = i.farmer_id AND o.season IS NULL AND o.credits_used > 0
  AND o.status IN ('PENDING_CONFIRMATION', 'CONFIRMED');

-- A seller's legacy claims can be attributed only if all season-linked earned
-- orders have the same season and token. Ambiguous claims stay null and block
-- new claims until an operator investigates them.
UPDATE redemption_claims c SET season = e.season, credit_token_id = e.token_id
FROM (SELECT seller_id, MIN(season) AS season, MIN(credit_token_id) AS token_id
      FROM market_orders
      WHERE status IN ('COMPLETED', 'DISPUTED') AND credits_used > 0
      GROUP BY seller_id
      HAVING COUNT(DISTINCT season) = 1 AND COUNT(DISTINCT credit_token_id) = 1
         AND SUM(CASE WHEN season IS NULL OR credit_token_id IS NULL THEN 1 ELSE 0 END) = 0) e
WHERE c.seller_id = e.seller_id AND c.season IS NULL;
