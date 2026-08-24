# 💸 Payment Service — Database Design

> **Database:** `payment_db`  
> **Purpose:** Track payments (single + batch), manage the outbox for async event publishing to Kafka.

---

## ER Diagram (Logical)

```
┌──────────────────────┐
│      payments        │
├──────────────────────┤
│ id          PK UUID  │
│ idempotency_key UUID │◄── UNIQUE — prevents duplicate transfers
│ from_account UUID    │──────► account_db.accounts.id
│ to_account   UUID    │──────► account_db.accounts.id
│ amount       DECIMAL │
│ category     VARCHAR │      ← SALARY|VENDOR|DIVIDEND|REFUND|GENERAL|TRANSFER
│ batch_id     UUID    │      ← NULL for single, set for batch
│ status       VARCHAR │      ← COMPLETED|FAILED
│ created_at   TIMESTAMP│
└──────────┬───────────┘
           │ 1
           │
           ▼ N          (1 payment = 1..3 outbox entries)
┌──────────────────────────────┐
│           outbox             │
├──────────────────────────────┤
│ id            PK BIGSERIAL   │
│ event_id      UUID UNIQUE    │◄── idempotency key per entry
│ aggregate_id  VARCHAR(100)   │◄── payment_id or batch_id
│ event_type    VARCHAR(100)   │◄── LedgerEntry|NotificationRequired
│ payload       JSONB          │◄── full event data
│ occurred_at   TIMESTAMP      │
│ status        VARCHAR(20)    │◄── PENDING→SENDING→PUBLISHED/ERROR
│ batch_id      VARCHAR(36)    │◄── set when batched for Kafka
│ error_message TEXT           │◄── set on ERROR
│ retry_count   INT DEFAULT 0  │
│ published_at  TIMESTAMP      │◄── when confirmed PUBLISHED
│ created_at    TIMESTAMP      │
│ updated_at    TIMESTAMP      │
└──────────────────────────────┘

┌──────────────────────┐
│   processed_batches  │
├──────────────────────┤
│ batch_id    PK UUID  │◄── idempotency — already consumed?
│ status      VARCHAR  │    COMMITTED|FAILED
│ error       TEXT     │
│ created_at  TIMESTAMP│
└──────────────────────┘
```

---

## Tables

### `payments`

Every transfer (single or part of a batch) gets one row. Completed payments are immutable.

| Column | Type | Constraints | Notes |
|--------|------|-------------|-------|
| `id` | UUID | PK | |
| `idempotency_key` | UUID | UNIQUE, NOT NULL | Client-provided. Same key = same result, no double-charge. |
| `from_account` | UUID | NOT NULL | Logical FK → account_db.accounts |
| `to_account` | UUID | NOT NULL | Logical FK → account_db.accounts |
| `amount` | DECIMAL(15,2) | NOT NULL, CHECK > 0 | |
| `remark` | VARCHAR(255) | | Per-transfer description. "Priya - July Salary", "Invoice #INV-202". |
| `category` | VARCHAR(30) | DEFAULT 'TRANSFER' | `SALARY`, `VENDOR`, `DIVIDEND`, `REFUND`, `GENERAL`, `TRANSFER` |
| `batch_id` | UUID | | NULL for single transfers. Set for each row in a batch. |
| `status` | VARCHAR(20) | DEFAULT 'COMPLETED' | `COMPLETED`, `FAILED` |
| `created_at` | TIMESTAMP | DEFAULT NOW() | |

```sql
CREATE TABLE payments (
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

CREATE INDEX idx_payments_from ON payments(from_account, created_at);
CREATE INDEX idx_payments_to ON payments(to_account, created_at);
CREATE INDEX idx_payments_batch ON payments(batch_id);
CREATE INDEX idx_payments_idem ON payments(idempotency_key);
```

### `outbox`

The heart of reliable event publishing. Every ledger entry notification and user notification is written here in the same DB transaction as the payment. Two schedulers (1s for notifications, 10s for ledger) pick up entries and publish to Kafka.

| Column | Type | Constraints | Notes |
|--------|------|-------------|-------|
| `id` | BIGSERIAL | PK | |
| `event_id` | UUID | UNIQUE, NOT NULL | Per-entry idempotency key |
| `aggregate_id` | VARCHAR(100) | NOT NULL | `payment_id` or `batch_id` |
| `event_type` | VARCHAR(100) | NOT NULL | `LedgerEntry` or `NotificationRequired` |
| `payload` | JSONB | NOT NULL | Full event data |
| `occurred_at` | TIMESTAMP | NOT NULL | When the business event happened |
| `status` | VARCHAR(20) | DEFAULT 'PENDING' | `PENDING` → `SENDING` → `PUBLISHED` / `ERROR` |
| `batch_id` | VARCHAR(36) | | Set when status → SENDING |
| `error_message` | TEXT | | Set when status → ERROR |
| `retry_count` | INT | DEFAULT 0 | Incremented on ERROR → PENDING retry |
| `published_at` | TIMESTAMP | | When Transaction Service confirmed COMMITTED |
| `created_at` | TIMESTAMP | DEFAULT NOW() | |
| `updated_at` | TIMESTAMP | DEFAULT NOW() | |

```sql
CREATE TABLE outbox (
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

CREATE INDEX idx_outbox_status_type ON outbox(status, event_type, created_at);
CREATE INDEX idx_outbox_batch ON outbox(batch_id);
```

### `processed_batches`

Tracks which ledger batches have already been consumed from Kafka `ledger-confirm` topic. Prevents duplicate processing of confirmations.

| Column | Type | Constraints | Notes |
|--------|------|-------------|-------|
| `batch_id` | UUID | PK | From Kafka message |
| `status` | VARCHAR(20) | NOT NULL | `COMMITTED`, `FAILED` |
| `error` | TEXT | | Error details if FAILED |
| `created_at` | TIMESTAMP | DEFAULT NOW() | |

```sql
CREATE TABLE processed_batches (
    batch_id        UUID PRIMARY KEY,
    status          VARCHAR(20) NOT NULL CHECK (status IN ('COMMITTED','FAILED')),
    error           TEXT,
    created_at      TIMESTAMP DEFAULT NOW()
);
```

---

## What Happens During a Transfer

```sql
-- All in ONE transaction:
BEGIN;

    -- 1. Record the payment
    INSERT INTO payments (idempotency_key, from_account, to_account, amount, remark, category, status)
    VALUES (:idemKey, :from, :to, :amount, :remark, 'TRANSFER', 'COMPLETED');

    -- 2. Outbox: LedgerEntry (DEBIT) — will be batched every 10s → Kafka
    INSERT INTO outbox (event_id, aggregate_id, event_type, payload, occurred_at, status)
    VALUES (gen_random_uuid(), :paymentId, 'LedgerEntry',
            '{"entryType":"DEBIT","accountId":":from","amount":":amount","reference":":paymentId","remark":":remark"}',
            NOW(), 'PENDING');

    -- 3. Outbox: LedgerEntry (CREDIT) — batched with #2
    INSERT INTO outbox (event_id, aggregate_id, event_type, payload, occurred_at, status)
    VALUES (gen_random_uuid(), :paymentId, 'LedgerEntry',
            '{"entryType":"CREDIT","accountId":":to","amount":":amount","reference":":paymentId","remark":":remark"}',
            NOW(), 'PENDING');

    -- 4. Outbox: NotificationRequired — polled every 1s → Kafka
    INSERT INTO outbox (event_id, aggregate_id, event_type, payload, occurred_at, status)
    VALUES (gen_random_uuid(), :paymentId, 'NotificationRequired',
            '{"fromAccount":":from","toAccount":":to","amount":":amount","remark":":remark","category":"TRANSFER"}',
            NOW(), 'PENDING');

COMMIT;
```

### Outbox Poller Queries

```sql
-- ⚡ 1-second poller: pick notifications
SELECT * FROM outbox 
WHERE status = 'PENDING' AND event_type = 'NotificationRequired'
ORDER BY created_at LIMIT 100
FOR UPDATE SKIP LOCKED;

-- 🐢 10-second poller: batch ledger entries
SELECT * FROM outbox 
WHERE status = 'PENDING' AND event_type = 'LedgerEntry'
ORDER BY created_at LIMIT 500
FOR UPDATE SKIP LOCKED;

-- After picking: mark SENDING
UPDATE outbox SET status = 'SENDING', batch_id = :batchId, updated_at = NOW()
WHERE id IN (:ids);

-- When confirmation arrives (COMMITTED):
UPDATE outbox SET status = 'PUBLISHED', published_at = NOW(), updated_at = NOW()
WHERE batch_id = :batchId;

-- When confirmation arrives (FAILED):
UPDATE outbox SET status = 'ERROR', error_message = :error, retry_count = retry_count + 1, updated_at = NOW()
WHERE batch_id = :batchId;

-- Cleanup (hourly): delete PUBLISHED entries older than 1 hour
DELETE FROM outbox WHERE status = 'PUBLISHED' AND published_at < NOW() - INTERVAL '1 hour';

-- Retry (every 30s): reset ERROR entries with retry_count < 3
UPDATE outbox SET status = 'PENDING', updated_at = NOW()
WHERE status = 'ERROR' AND retry_count < 3 AND updated_at < NOW() - INTERVAL '30 seconds';
```

---

## Cross-Service Relationships

```
Payment DB                         Account DB
payments(from_account)      ──►   accounts(id)
payments(to_account)        ──►   accounts(id)

Payment DB (outbox) ──Kafka──►  Transaction DB (unified_ledger)
Payment DB (outbox) ──Kafka──►  Notification DB (notifications)
```
