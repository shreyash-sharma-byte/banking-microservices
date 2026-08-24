CREATE TABLE IF NOT EXISTS notifications (
    id              UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id         UUID NOT NULL,
    account_id      UUID,
    channel         VARCHAR(10) NOT NULL CHECK (channel IN ('SMS','EMAIL')),
    template        VARCHAR(50) NOT NULL,
    message         TEXT NOT NULL,
    reference_id    VARCHAR(255),
    status          VARCHAR(20) DEFAULT 'SENT' CHECK (status IN ('SENT','FAILED')),
    error_message   TEXT,
    correlation_id  VARCHAR(36),
    created_at      TIMESTAMP DEFAULT NOW()
);

CREATE INDEX IF NOT EXISTS idx_notif_user ON notifications(user_id, created_at DESC);
CREATE INDEX IF NOT EXISTS idx_notif_ref ON notifications(reference_id);
