# 📊 Transaction Service — Database Design

> **Database:** `transaction_db`  
> **Purpose:** Unified append-only ledger — the single source of truth for ALL money movement across the platform. Immutable. No UPDATEs, no DELETEs.

---

## ER Diagram (Logical)

```
┌──────────────────────────────────────┐
│           unified_ledger             │
├──────────────────────────────────────┤
│ id              PK BIGSERIAL         │
│ event_id        UUID UNIQUE          │◄── from source service (idempotency)
│ source_service  VARCHAR(50)          │◄── "payment", later "loan", "card"
│ account_id      UUID NOT NULL        │────► account_db.accounts.id
│ entry_type      VARCHAR(10)          │◄── DEBIT | CREDIT
│ amount          DECIMAL(15,2)        │
│ reference       VARCHAR(255)         │◄── payment_id, loan_id, etc.
│ occurred_at     TIMESTAMP            │◄── when it actually happened
│ ingested_at     TIMESTAMP            │◄── when we received it
│ correlation_id  VARCHAR(36)          │
└──────────────────────────────────────┘
          │
          │ APPEND ONLY — no UPDATE, no DELETE
          │
          ▼

┌──────────────────────────────────────┐
│         processed_batches            │
├──────────────────────────────────────┤
│ batch_id        VARCHAR(36) PK       │◄── from Kafka ledger-batches
│ entry_count     INT                  │
│ status          VARCHAR(20)          │◄── COMMITTED | FAILED
│ error_message   TEXT                 │
│ processed_at    TIMESTAMP            │
└──────────────────────────────────────┘
```

---

## Tables

### `unified_ledger`

The golden record. Every debit and credit from every service ends up here. Consumers: user transaction history, admin dashboard, auditor reports, fraud detection (Phase 2).

| Column | Type | Constraints | Notes |
|--------|------|-------------|-------|
| `id` | BIGSERIAL | PK | Auto-increment |
| `event_id` | UUID | UNIQUE, NOT NULL | Idempotency key from the source service |
| `source_service` | VARCHAR(50) | NOT NULL | `payment` (MVP), `loan`, `card` (Phase 2) |
| `account_id` | UUID | NOT NULL | Logical FK → account_db.accounts |
| `entry_type` | VARCHAR(10) | NOT NULL, CHECK | `DEBIT` or `CREDIT` |
| `amount` | DECIMAL(15,2) | NOT NULL, CHECK > 0 | |
| `reference` | VARCHAR(255) | | Payment ID, loan ID, etc. |
| `occurred_at` | TIMESTAMP | NOT NULL | When the transaction actually happened (from event) |
| `ingested_at` | TIMESTAMP | DEFAULT NOW() | When Transaction Service received & stored it |
| `correlation_id` | VARCHAR(36) | | For distributed tracing |

```sql
-- Partitioned by month on occurred_at.
-- Querying "June transactions" only scans June's partition.
CREATE TABLE unified_ledger (
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
    
    PRIMARY KEY (id, occurred_at),       -- partition key must be in PK
    UNIQUE (event_id, occurred_at)       -- unique constraint needs partition key
) PARTITION BY RANGE (occurred_at);

-- Monthly partitions (create ahead or via cron):
CREATE TABLE unified_ledger_2026_07 PARTITION OF unified_ledger
    FOR VALUES FROM ('2026-07-01') TO ('2026-08-01');
CREATE TABLE unified_ledger_2026_08 PARTITION OF unified_ledger
    FOR VALUES FROM ('2026-08-01') TO ('2026-09-01');

-- Primary query: "Show me transactions for account X, newest first"
CREATE INDEX idx_ledger_account ON unified_ledger(account_id, occurred_at DESC);

-- Admin/auditor: "All transactions on date Y"
CREATE INDEX idx_ledger_date ON unified_ledger(occurred_at);

-- Deduplication check
CREATE INDEX idx_ledger_event ON unified_ledger(event_id);

-- CRITICAL: No UPDATE or DELETE ever. Application enforces this.
-- Database-level guard:
CREATE OR REPLACE RULE no_update_unified_ledger AS ON UPDATE TO unified_ledger
DO INSTEAD NOTHING;

CREATE OR REPLACE RULE no_delete_unified_ledger AS ON DELETE TO unified_ledger
DO INSTEAD NOTHING;
```

### `processed_batches`

Tracks which Kafka batches have been consumed. Prevents double-processing.

| Column | Type | Constraints | Notes |
|--------|------|-------------|-------|
| `batch_id` | VARCHAR(36) | PK | From Kafka `ledger-batches` message |
| `entry_count` | INT | NOT NULL | Number of entries in this batch |
| `status` | VARCHAR(20) | NOT NULL | `COMMITTED` or `FAILED` |
| `error_message` | TEXT | | Error details if FAILED |
| `processed_at` | TIMESTAMP | DEFAULT NOW() | |

```sql
CREATE TABLE processed_batches (
    batch_id        VARCHAR(36) PRIMARY KEY,
    entry_count     INT NOT NULL,
    status          VARCHAR(20) NOT NULL CHECK (status IN ('COMMITTED','FAILED')),
    error_message   TEXT,
    processed_at    TIMESTAMP DEFAULT NOW()
);
```

---

## Bulk Insert — How Batches Are Consumed

```java
// Kafka consumer receives: { batchId, entries: [{eventId, accountId, type, amount, ...}] }

@Transactional
public void consumeBatch(BatchPayload batch) {
    // 1. Idempotency: already processed?
    if (batchRepo.existsById(batch.batchId)) {
        log.warn("Duplicate batch {}, skipping", batch.batchId);
        return;  // Still publish confirmation (idempotent on other side)
    }
    
    try {
        // 2. Bulk INSERT — 1 SQL, 500 rows
        jdbcTemplate.batchUpdate(
            "INSERT INTO unified_ledger (event_id, source_service, account_id, entry_type, amount, reference, occurred_at, correlation_id) VALUES (?, ?, ?, ?, ?, ?, ?, ?) ON CONFLICT (event_id) DO NOTHING",
            batch.entries
        );
        
        // 3. Record batch as processed
        batchRepo.save(new ProcessedBatch(batch.batchId, batch.entries.size(), "COMMITTED"));
        
        // 4. Publish confirmation to Kafka
        kafkaTemplate.send("ledger-confirm", new BatchConfirmation(batch.batchId, "COMMITTED"));
        
        log.info("Batch {} committed: {} entries", batch.batchId, batch.entries.size());
        
    } catch (Exception e) {
        log.error("🔴 Batch {} FAILED: {}", batch.batchId, e.getMessage(), e);
        batchRepo.save(new ProcessedBatch(batch.batchId, 0, "FAILED", e.getMessage()));
        kafkaTemplate.send("ledger-confirm", new BatchConfirmation(batch.batchId, "FAILED", e.getMessage()));
    }
}
```

---

## Query Patterns

```sql
-- Priya: "Show my last 20 transactions"
SELECT * FROM unified_ledger 
WHERE account_id = 'acc_001' 
ORDER BY occurred_at DESC 
LIMIT 20 OFFSET 0;

-- Priya: "Show my debits from last month above ₹5000"
SELECT * FROM unified_ledger 
WHERE account_id = 'acc_001' 
  AND entry_type = 'DEBIT' 
  AND amount > 5000
  AND occurred_at BETWEEN '2026-06-01' AND '2026-06-30'
ORDER BY occurred_at DESC;

-- Rajesh: "Daily summary for June 15"
SELECT entry_type, COUNT(*) as txn_count, SUM(amount) as total_volume
FROM unified_ledger 
WHERE occurred_at::date = '2026-06-15'
GROUP BY entry_type;

-- Auditor: "All transactions above ₹10L in Q1 2026"
SELECT * FROM unified_ledger 
WHERE amount >= 1000000 
  AND occurred_at BETWEEN '2026-01-01' AND '2026-03-31'
ORDER BY occurred_at;
```

---

## Cross-Service Relationships

```
Transaction DB                      Account DB
unified_ledger(account_id)  ──►    accounts(id)

Transaction DB ←──Kafka── Payment DB (outbox → ledger-batches)
Transaction DB ──Kafka──► Payment DB (ledger-confirm)
```
