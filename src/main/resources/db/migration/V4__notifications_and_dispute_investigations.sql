CREATE TABLE user_notifications (
    id BIGSERIAL PRIMARY KEY,
    user_id BIGINT NOT NULL REFERENCES users(user_id),
    kind VARCHAR(80) NOT NULL,
    message VARCHAR(300) NOT NULL,
    target_url VARCHAR(120),
    read_at TIMESTAMP,
    created_at TIMESTAMP NOT NULL
);
CREATE INDEX idx_notifications_user_created ON user_notifications(user_id, created_at DESC);

CREATE TABLE dispute_investigations (
    id BIGSERIAL PRIMARY KEY,
    dispute_type VARCHAR(20) NOT NULL,
    record_id BIGINT NOT NULL,
    status VARCHAR(20) NOT NULL,
    updated_at TIMESTAMP NOT NULL,
    UNIQUE(dispute_type, record_id)
);
CREATE TABLE dispute_investigation_events (
    id BIGSERIAL PRIMARY KEY,
    investigation_id BIGINT NOT NULL REFERENCES dispute_investigations(id),
    actor_user_id BIGINT NOT NULL REFERENCES users(user_id),
    status VARCHAR(20) NOT NULL,
    note VARCHAR(1000) NOT NULL,
    created_at TIMESTAMP NOT NULL
);
CREATE INDEX idx_dispute_events_investigation ON dispute_investigation_events(investigation_id, created_at);

INSERT INTO dispute_investigations(dispute_type, record_id, status, updated_at)
SELECT 'HANDOVER', distribution_id, 'OPEN', COALESCE(disputed_at, created_at)
FROM distribution_logs WHERE disputed = TRUE;
INSERT INTO dispute_investigations(dispute_type, record_id, status, updated_at)
SELECT 'ORDER', order_id, 'OPEN', COALESCE(disputed_at, created_at)
FROM market_orders WHERE disputed_at IS NOT NULL;
INSERT INTO dispute_investigation_events(investigation_id, actor_user_id, status, note, created_at)
SELECT i.id, d.farmer_id, 'OPEN', 'Farmer raised dispute before investigation workflow', COALESCE(d.disputed_at, d.created_at)
FROM dispute_investigations i JOIN distribution_logs d ON i.dispute_type = 'HANDOVER' AND i.record_id = d.distribution_id;
INSERT INTO dispute_investigation_events(investigation_id, actor_user_id, status, note, created_at)
SELECT i.id, o.farmer_id, 'OPEN', 'Farmer raised dispute before investigation workflow', COALESCE(o.disputed_at, o.created_at)
FROM dispute_investigations i JOIN market_orders o ON i.dispute_type = 'ORDER' AND i.record_id = o.order_id;
