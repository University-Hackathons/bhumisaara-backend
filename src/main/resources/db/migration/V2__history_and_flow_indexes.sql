CREATE INDEX IF NOT EXISTS ix_distribution_officer_history
    ON distribution_logs(officer_id, created_at DESC, distribution_id DESC);
CREATE INDEX IF NOT EXISTS ix_distribution_farmer_history
    ON distribution_logs(farmer_id, created_at DESC, distribution_id DESC);
CREATE INDEX IF NOT EXISTS ix_distribution_national_history
    ON distribution_logs(created_at DESC, distribution_id DESC);
CREATE INDEX IF NOT EXISTS ix_transfer_national_history
    ON batch_transfers(created_at DESC, transfer_id DESC);
CREATE INDEX IF NOT EXISTS ix_transfer_custody
    ON batch_transfers(token_id, to_officer_id);
