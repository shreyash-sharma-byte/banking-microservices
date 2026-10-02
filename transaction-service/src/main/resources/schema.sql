CREATE TABLE IF NOT EXISTS unified_ledger (
    id              BIGSERIAL,
    event_id        UUID NOT NULL,
    source_service  VARCHAR(50) NOT NULL CHECK (source_service IN ('payment','loan','card')),
    account_id      UUID NOT NULL,
    entry_type      VARCHAR(10) NOT NULL CHECK (entry_type IN ('DEBIT','CREDIT')),
    amount          DECIMAL(15,2) NOT NULL CHECK (amount > 0),
    reference       VARCHAR(255),
    occurred_at     TIMESTAMP NOT NULL,
    ingested_at     TIMESTAMP DEFAULT NOW(),
    correlation_id  VARCHAR(36),
    PRIMARY KEY (id, occurred_at),
    UNIQUE (event_id, occurred_at)
) PARTITION BY RANGE (occurred_at);

CREATE TABLE IF NOT EXISTS unified_ledger_2026_08 PARTITION OF unified_ledger
    FOR VALUES FROM ('2026-08-01') TO ('2026-09-01');

-- Safety net — see the same note in account-service/schema.sql. Without this,
-- the ledger consumer fails on the first entry occurring outside the declared
-- months and every batch is marked FAILED, so transfers complete at the bank
-- but never reach the ledger. Concrete months go before this declaration.
CREATE TABLE IF NOT EXISTS unified_ledger_default PARTITION OF unified_ledger DEFAULT;

CREATE TABLE IF NOT EXISTS processed_batches (
    batch_id        UUID PRIMARY KEY,
    entry_count     INT NOT NULL DEFAULT 0,
    status          VARCHAR(20) NOT NULL CHECK (status IN ('COMMITTED','FAILED')),
    error_message   TEXT,
    processed_at    TIMESTAMP DEFAULT NOW()
);

CREATE INDEX IF NOT EXISTS idx_ledger_account ON unified_ledger(account_id, occurred_at DESC);
CREATE INDEX IF NOT EXISTS idx_ledger_date ON unified_ledger(occurred_at);
CREATE INDEX IF NOT EXISTS idx_ledger_event ON unified_ledger(event_id);
