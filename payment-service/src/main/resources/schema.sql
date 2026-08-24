CREATE TABLE IF NOT EXISTS payments (
    id              UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    idempotency_key UUID UNIQUE NOT NULL,
    from_account    UUID NOT NULL,
    to_account      UUID NOT NULL,
    amount          DECIMAL(15,2) NOT NULL CHECK (amount > 0),
    remark          VARCHAR(255),
    category        VARCHAR(30) DEFAULT 'TRANSFER',
    batch_id        UUID,
    status          VARCHAR(20) DEFAULT 'COMPLETED' CHECK (status IN ('COMPLETED','FAILED')),
    created_at      TIMESTAMP DEFAULT NOW()
);

CREATE TABLE IF NOT EXISTS outbox (
    id              BIGSERIAL PRIMARY KEY,
    event_id        UUID UNIQUE NOT NULL,
    aggregate_id    VARCHAR(100) NOT NULL,
    event_type      VARCHAR(100) NOT NULL CHECK (event_type IN ('LedgerEntry','NotificationRequired')),
    payload         JSONB NOT NULL,
    occurred_at     TIMESTAMP NOT NULL,
    status          VARCHAR(20) DEFAULT 'PENDING' CHECK (status IN ('PENDING','SENDING','PUBLISHED','ERROR')),
    batch_id        VARCHAR(36),
    error_message   TEXT,
    retry_count     INT DEFAULT 0,
    published_at    TIMESTAMP,
    created_at      TIMESTAMP DEFAULT NOW(),
    updated_at      TIMESTAMP DEFAULT NOW()
);

CREATE TABLE IF NOT EXISTS processed_batches (
    batch_id        UUID PRIMARY KEY,
    status          VARCHAR(20) NOT NULL CHECK (status IN ('COMMITTED','FAILED')),
    error           TEXT,
    created_at      TIMESTAMP DEFAULT NOW()
);

CREATE INDEX IF NOT EXISTS idx_payments_idem ON payments(idempotency_key);
CREATE INDEX IF NOT EXISTS idx_payments_from ON payments(from_account, created_at);
CREATE INDEX IF NOT EXISTS idx_outbox_status_type ON outbox(status, event_type, created_at);
CREATE INDEX IF NOT EXISTS idx_outbox_batch ON outbox(batch_id);
