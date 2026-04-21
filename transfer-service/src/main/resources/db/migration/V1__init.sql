CREATE TABLE IF NOT EXISTS transfers (
    id                     VARCHAR(36) PRIMARY KEY,
    source_account_id      VARCHAR(36) NOT NULL,
    destination_account_id VARCHAR(36) NOT NULL,
    amount                 NUMERIC(19,2) NOT NULL,
    status                 VARCHAR(20) NOT NULL DEFAULT 'PENDING',
    callback_url           TEXT,
    user_id                VARCHAR(36) NOT NULL,
    created_at             TIMESTAMPTZ DEFAULT NOW(),
    updated_at             TIMESTAMPTZ DEFAULT NOW()
);
CREATE INDEX IF NOT EXISTS idx_transfers_user ON transfers(user_id);
