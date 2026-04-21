CREATE TABLE IF NOT EXISTS accounts (
    id            VARCHAR(36) PRIMARY KEY,
    owner_id      VARCHAR(36) NOT NULL,
    balance       NUMERIC(19, 2) NOT NULL DEFAULT 0,
    created_at    TIMESTAMPTZ DEFAULT NOW()
);

CREATE TABLE IF NOT EXISTS transfer_idempotency (
    account_id   VARCHAR(36) NOT NULL,
    transfer_id  VARCHAR(36) NOT NULL,
    created_at   TIMESTAMPTZ DEFAULT NOW(),
    PRIMARY KEY (account_id, transfer_id)
);

CREATE TABLE IF NOT EXISTS outbox (
    id           UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    aggregate_id VARCHAR NOT NULL,
    event_type   VARCHAR NOT NULL,
    payload      TEXT NOT NULL,
    published    BOOLEAN DEFAULT FALSE,
    created_at   TIMESTAMPTZ DEFAULT NOW()
);

CREATE INDEX IF NOT EXISTS idx_outbox_unpublished ON outbox(published) WHERE published = FALSE;
