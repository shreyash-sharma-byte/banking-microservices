# 📐 Architecture Decision Records (ADR)

> Banking Microservices Platform — Key design decisions and why we made them.

---

## ADR-001: Keep Payment + Transaction as Separate Services

**Date:** 2026-07-18  
**Status:** ✅ Accepted

### Context
The Payment Service orchestrates money transfers (validate → debit → credit). The Transaction Service maintains the append-only unified ledger. Should they be merged into one service?

### Decision
**Keep them separate.** Payment handles orchestration and mutable state. Transaction handles immutable record-keeping.

### Rationale
- In Phase 2+, multiple services (Loan, Card, Interest Engine) will write to the ledger — Transaction Service becomes the single source of truth.
- Payment is write-heavy (transfers), Transaction is read-heavy (history, reporting) — independent scaling.
- Immutability is enforced by the service boundary, not code discipline.
- Teaches 3-step Saga with compensating actions.

### Consequences
- Additional network hops per transfer
- +1 PostgreSQL instance (acceptable for learning)

> **See ADR-005:** Saga is deferred to Phase 2. MVP uses atomic Account.transfer() — no Saga in Payment Service.

---

## ADR-002: Batch Sync via Outbox Pattern for Ledger Entries

**Date:** 2026-07-18  
**Status:** ✅ Accepted

### Context
Every money movement (transfer, loan disbursal, card spend) must be recorded in the Transaction Service's unified ledger. Calling Transaction Service synchronously for every transaction creates too many network calls and tight coupling.

### Decision
**Use the Outbox Pattern with batch synchronization and confirmation callback.**

Each service writes ledger entries to a local `outbox` table with status `PENDING`. A scheduler picks up PENDING entries, marks them `SENDING`, and publishes them in a batch to Kafka. Transaction Service bulk-inserts and publishes a confirmation. The source service then marks entries as `PUBLISHED`.

### Full Lifecycle (3-State Machine)

```
  ┌─────────┐     Scheduler      ┌─────────┐   Txn Svc confirms  ┌──────────┐
  │ PENDING │ ────────────────►  │ SENDING │ ─────────────────►  │PUBLISHED │
  │         │    picks up        │         │   (Kafka callback)  │          │
  └─────────┘                    └─────────┘                     └──────────┘
       │                              │                               │
       │ "I have a new               │ "Batch is on its              │ "Txn Service has
       │  ledger entry               │  way to Kafka,                │  committed it.
       │  to sync"                   │  don't pick me up             │  Safe to delete."
       │                             │  again"                       │
       └─────────────────────────────┴───────────────────────────────┘
                  Next scheduler run SKIPS SENDING entries
```

### Why 3 States Instead of a Boolean?

| Design | Problem |
|--------|---------|
| `published BOOLEAN` | No way to know if an entry was sent to Kafka but Transaction Service hasn't committed yet. If scheduler runs again, it re-sends duplicates. |
| `status: PENDING → SENDING → PUBLISHED` | SENDING = "on its way, don't touch". PUBLISHED = "confirmed committed in Transaction Service, safe to delete". |

### Architecture (with Confirmation Callback)

```
Payment Service                            Transaction Service
┌──────────────────────────────┐           ┌──────────────────────────┐
│                              │           │                          │
│  outbox entries              │           │                          │
│  ┌────────────────────┐      │           │                          │
│  │ status=PENDING (3) │      │           │                          │
│  │ status=SENDING (0) │      │           │                          │
│  └────────┬───────────┘      │           │                          │
│           │                  │           │                          │
│  ⏰ Scheduler (10s)          │           │                          │
│  1. SELECT * WHERE           │           │                          │
│     status = 'PENDING' ◄─────┤ (skips    │                          │
│                              │  SENDING) │                          │
│  2. UPDATE status='SENDING'  │           │                          │
│     WHERE id IN (...)        │           │                          │
│                              │           │                          │
│  3. Publish batch ───────────┼──►KAFKA──►│ 4. Consume batch        │
│     topic: ledger-batches    │           │                          │
│                              │           │ 5. Bulk INSERT           │
│     ┌──────────────────┐     │           │    (all or nothing)      │
│     │ batch_id: abc123 │     │           │                          │
│     │ entries: [3]     │     │           │                          │
│     └──────────────────┘     │           │                          │
│                              │           │ 6. Publish confirmation  │
│  7. Consume confirmation ◄───┼──KAFKA◄───┤    topic: ledger-confirm│
│                              │           │    { batch_id: abc123,   │
│  8. UPDATE status='PUBLISHED'│           │      status: COMMITTED } │
│     WHERE batch_id='abc123'  │           │                          │
│                              │           │                          │
│  9. Cleanup (later):         │           │                          │
│     DELETE WHERE             │           │                          │
│     status='PUBLISHED'       │           │                          │
│     AND created_at < NOW()-1h│           │                          │
└──────────────────────────────┘           └──────────────────────────┘
```

### Sync Trigger
```java
@Scheduled(fixedRate = 10_000)   // Every 10 seconds
public void syncLedgerEntries() {
    // 1. Pick up only PENDING entries (skip SENDING and ERROR)
    List<OutboxEntry> pending = outboxRepo
        .findByStatus(OutboxStatus.PENDING);
    
    if (pending.isEmpty()) return;
    
    // 2. Mark them SENDING immediately (so next run skips them)
    String batchId = UUID.randomUUID().toString();
    outboxRepo.updateStatus(OutboxStatus.SENDING, batchId, pending.ids());
    
    // 3. Publish ONE Kafka message
    BatchLedgerPayload batch = new BatchLedgerPayload(batchId, pending);
    kafkaTemplate.send("ledger-batches", batch);
}
```

### Error Recovery Flow

When Transaction Service fails to process a batch (DB down, constraint violation, etc.):

```
Payment Service                              Transaction Service
┌────────────────────────────────┐           ┌────────────────────────┐
│                                │           │                        │
│  Outbox: status=SENDING        │           │ 6. Receive batch       │
│  batch_id=abc123               │           │                        │
│                                │           │ 7. Bulk INSERT fails!  │
│                                │           │    (e.g., DB deadlock, │
│                                │           │     schema mismatch)   │
│                                │           │                        │
│                                │           │ 8. Log ERROR with full │
│  9. Consume error confirmation◄├──KAFKA◄───┤    stack trace:       │
│     { batch_id: abc123,        │           │ log.error("Batch {}   │
│       status: FAILED,          │           │   failed: {}",         │
│       error: "deadlock",       │           │   batchId, e.getMessage│
│       failed_at: ... }         │           │   , e);               │
│                                │           │                        │
│ 10. UPDATE outbox              │           │ Publish to             │
│     SET status='ERROR',        │           │ ledger-confirm:        │
│         error_message=?,       │           │ { batch_id: abc123,    │
│         retry_count=retry_count+1        │   status: FAILED,       │
│     WHERE batch_id='abc123'    │           │   error: "deadlock" }  │
│                                │           │                        │
│  ⚠ ERROR entries are NEVER    │           │                        │
│    auto-deleted. They stay    │           │                        │
│    for manual investigation.  │           │                        │
│                                │           │                        │
│ 11. Retry (separate scheduler):│           │                        │
│     SELECT * FROM outbox       │           │                        │
│     WHERE status='ERROR'       │           │                        │
│     AND retry_count < 3        │           │                        │
│     AND updated_at < NOW()-30s │           │                        │
│     → Reset to PENDING         │           │                        │
│     → Will be picked up next   │           │                        │
│       sync cycle               │           │                        │
└────────────────────────────────┘           └────────────────────────┘
```

### Full State Machine

```
                    ┌──────────────────────────────────────┐
                    │                                      │
                    ▼                                      │
   ┌─────────┐  scheduler   ┌─────────┐  txn svc     ┌──────────┐  cleanup  ┌─────────┐
   │ PENDING │─────────────►│ SENDING │─────────────►│PUBLISHED │──────────►│ DELETED │
   │         │              │         │  confirms     │          │ (hourly)  │         │
   └─────────┘              └────┬────┘               └──────────┘           └─────────┘
                                 │                        ↑
                                 │ txn svc FAILS          │ retry succeeds
                                 ▼                        │
                            ┌─────────┐                   │
                            │  ERROR  │───────────────────┘
                            │         │  retry scheduler:
                            │ (kept   │  retry_count<3?
                            │  forever│  reset to PENDING
                            │  unless │
                            │  retried)│
                            └─────────┘
                                 │
                                 │ retry_count >= 3
                                 ▼
                            ┌──────────────┐
                            │ ERROR (DEAD) │
                            │ Manual review │
                            │ required      │
                            └──────────────┘
```

### Error Logging Convention

```java
// Transaction Service — when batch fails
@KafkaListener(topics = "ledger-batches")
public void consumeBatch(BatchLedgerPayload batch) {
    try {
        // ... bulk INSERT ...
        
        log.info("Batch {} committed: {} entries in {}ms", 
            batch.batchId, batch.entries.size(), duration);
        
        // Publish success
        kafkaTemplate.send("ledger-confirm", 
            new BatchConfirmation(batch.batchId, "COMMITTED"));
            
    } catch (DataAccessException e) {
        // 🔴 RED LOG — full context for debugging
        log.error("""
            ╔══════════════════════════════════════════╗
            ║  BATCH PROCESSING FAILED                ║
            ╠══════════════════════════════════════════╣
            ║  batch_id  : {}                         ║
            ║  entries   : {}                         ║
            ║  error     : {}                         ║
            ║  service   : transaction-service        ║
            ╚══════════════════════════════════════════╝
            """, batch.batchId, batch.entries.size(), e.getMessage(), e);
        
        // Publish failure confirmation
        kafkaTemplate.send("ledger-confirm",
            new BatchConfirmation(batch.batchId, "FAILED", e.getMessage()));
    }
}

// Payment Service — when receiving error confirmation
@KafkaListener(topics = "ledger-confirm")
public void handleConfirmation(BatchConfirmation confirm) {
    if ("COMMITTED".equals(confirm.status)) {
        outboxRepo.markPublished(confirm.batchId);
        log.info("Batch {} confirmed COMMITTED", confirm.batchId);
    } else {
        outboxRepo.markError(confirm.batchId, confirm.error);
        log.error("🔴 Batch {} FAILED: {} — outbox entries marked ERROR for investigation",
            confirm.batchId, confirm.error);
    }
}
```

### Kafka Topics (Updated)

| Topic | Direction | Purpose | Timing |
|-------|-----------|---------|--------|
| `payment-events` | Payment → Notification | Transfer completed alert (per-txn) | Immediate |
| `ledger-batches` | Payment → Transaction | Bulk ledger entries to record | Every 10s |
| `ledger-confirm` | Transaction → Payment | Confirmation: `COMMITTED` or `FAILED` | After bulk INSERT |

### Rationale
- **No lost entries:** `SENDING` status prevents the scheduler from re-picking in-flight entries.
- **ERROR entries never auto-deleted:** They stay for manual investigation. Only `PUBLISHED` entries are cleaned up.
- **Retry with backoff:** ERROR entries with `retry_count < 3` are reset to PENDING after 30s, picked up by next sync.
- **Confirmation callback:** Source service only deletes outbox entries after Transaction Service confirms COMMITTED.
- **Idempotency at every level:** `batch_id` prevents duplicate INSERTs in Transaction Service. `ON CONFLICT DO NOTHING` means retries are safe.

### Consequences
- Ledger is **eventually consistent** — max 10-second delay + processing time.
- ERROR entries persist until manually investigated or auto-retried successfully.
- If Transaction Service is down for 30 minutes, outbox entries accumulate in PENDING/SENDING, then batch sync when recovered.

---

## ADR-003: Outbox Table Design

**Date:** 2026-07-18  
**Status:** ✅ Accepted

### Decision
Every service that writes to the unified ledger has this table:

```sql
CREATE TABLE outbox (
    id            BIGSERIAL PRIMARY KEY,
    event_id      UUID NOT NULL UNIQUE,        -- idempotency key
    aggregate_id  VARCHAR(100) NOT NULL,        -- e.g., payment_id, loan_id
    event_type    VARCHAR(100) NOT NULL,        -- "PaymentCompleted", "LoanDisbursed"
    payload       JSONB NOT NULL,               -- full ledger entry data
    occurred_at   TIMESTAMP NOT NULL,           -- when the business event happened
    status        VARCHAR(20) NOT NULL DEFAULT 'PENDING',  -- PENDING | SENDING | PUBLISHED | ERROR
    batch_id      VARCHAR(36),                  -- set when status → SENDING
    error_message TEXT,                         -- set when status → ERROR (stack trace / reason)
    retry_count   INT DEFAULT 0,                -- incremented on each ERROR → PENDING retry
    published_at  TIMESTAMP,                    -- when Transaction Svc confirmed COMMITTED
    created_at    TIMESTAMP DEFAULT NOW(),
    updated_at    TIMESTAMP DEFAULT NOW()       -- track last status change
);

CREATE INDEX idx_outbox_status ON outbox(status, created_at);
CREATE INDEX idx_outbox_batch ON outbox(batch_id);
```

### Status Lifecycle

| Status | Meaning | Who Sets It | Auto-Deleted? |
|--------|---------|------------|:---:|
| **PENDING** | New entry, waiting for scheduler | Business operation (INSERT) | ❌ |
| **SENDING** | Scheduler sent to Kafka, awaiting confirmation | Scheduler (UPDATE) | ❌ |
| **PUBLISHED** | Transaction Service confirmed COMMITTED | Confirmation consumer (UPDATE) | ✅ (after 1 hour) |
| **ERROR** | Transaction Service returned FAILED | Confirmation consumer (UPDATE) | ❌ **NEVER** |

### Why ERROR Entries Are Never Auto-Deleted

- They represent failed attempts to record ledger entries — data integrity issue.
- Someone (Rajesh, the ops manager) must investigate: what failed? why? is data missing from the ledger?
- Only after investigation and resolution should they be manually cleaned up.

### Retry Logic for ERROR Entries

```java
@Scheduled(fixedRate = 30_000)  // Every 30 seconds
public void retryErrors() {
    List<OutboxEntry> retryable = outboxRepo.findByStatusAndRetryCount(
        OutboxStatus.ERROR, 3);  // retry_count < 3
    
    for (OutboxEntry entry : retryable) {
        log.warn("Retrying outbox entry {} (attempt {})", 
            entry.eventId, entry.retryCount + 1);
        outboxRepo.updateStatusAndIncrementRetry(
            entry.getId(), OutboxStatus.PENDING);
    }
    // Next sync cycle (10s) will pick up PENDING entries
}
```

### Why 3 States, Not 2?

A simple boolean `published` has a dangerous gap:

```
❌ Boolean design:
   published=false  →  scheduler sends to Kafka  →  published=true
                                                      ↑
   If Transaction Service crashes here, we marked it "done"
   but it's NOT in the ledger. Entry is lost.

✅ 3-State design:
   PENDING  →  SENDING  →  PUBLISHED
                ↑              ↑
        "On its way,     "Transaction Service
         don't re-pick"   confirmed commit"
```

### SQL Patterns

```sql
-- Scheduler: pick up PENDING entries (skip SENDING)
SELECT * FROM outbox WHERE status = 'PENDING' ORDER BY created_at LIMIT 500;

-- Scheduler: mark as SENDING (atomic — prevents re-pick)
UPDATE outbox SET status = 'SENDING', batch_id = ? WHERE id IN (...);

-- Confirmation consumer: mark as PUBLISHED
UPDATE outbox SET status = 'PUBLISHED', published_at = NOW() WHERE batch_id = ?;

-- Cleanup job (runs separately, e.g., every hour)
DELETE FROM outbox WHERE status = 'PUBLISHED' AND published_at < NOW() - INTERVAL '1 hour';
```

### Why JSONB for payload?
- Schema flexibility — Payment entries look different from Loan entries.
- Transaction Service can still extract structured fields from JSONB for the unified ledger.

### Retention
- Outbox entries: deleted 1 hour after PUBLISHED (already confirmed in Transaction Service).
- Kafka: retain `ledger-batches` and `ledger-confirm` topics for 7 days.

---

## ADR-004: Dual Outbox Schedulers (1s for Notifications, 10s for Ledger)

**Date:** 2026-07-18  
**Status:** ✅ Accepted

### Decision
Payment Service runs TWO outbox schedulers with different intervals targeting different event types:

| Scheduler | Interval | Event Type | Kafka Topic | Consumer |
|-----------|----------|------------|-------------|----------|
| ⚡ Notification Poller | **1 second** | `NotificationRequired` | `payment-events` | Notification Service |
| 🐢 Ledger Poller | **10 seconds** | `LedgerEntry` | `ledger-batches` | Transaction Service |

Both use `SELECT ... FOR UPDATE SKIP LOCKED` for safe multi-instance operation.

### Rationale
- **Notifications need near-real-time delivery.** Users expect SMS within 1-2 seconds of a transaction. A 10-second delay is a bad UX.
- **Ledger entries can tolerate delay.** The unified ledger is 10 seconds behind — acceptable for reporting/audit.
- **Same outbox table, different queries.** No need for separate infrastructure. Just filter by `event_type`.

### Three Event Streams
```
Payment Service:
  Transfer completes → local DB (payment + 3 outbox entries)
  
  ⚡ 1s poller → picks NotificationRequired → Kafka "payment-events"
  🐢 10s poller → picks LedgerEntry (×2) → batches → Kafka "ledger-batches"
  
  Confirmation listener ← Kafka "ledger-confirm" ← Transaction Service
```

### SKIP LOCKED for Multi-Instance Safety
```sql
SELECT * FROM outbox 
WHERE status = 'PENDING' AND event_type = 'NotificationRequired'
ORDER BY created_at LIMIT 100
FOR UPDATE SKIP LOCKED
```
If 3 Payment Service instances run the poller simultaneously, each locks different rows. No duplicates.

---

## ADR-005: Atomic Transfer — No Saga in Payment Service (Saga Deferred to Phase 2)

**Date:** 2026-07-18  
**Status:** ✅ Accepted

### Decision
**Account Service exposes a single `POST /accounts/transfer` endpoint that atomically debits and credits in one database transaction.** Payment Service makes 1 REST call, not 2. No Saga in MVP.

### Before (Forced Saga — REJECTED)
```
Payment ──REST──► Account.debit(from, amount)
        ──REST──► Account.credit(to, amount)
        ──Compensate if credit fails──► Account.credit(from, amount)  // reverse debit

Problem: Both accounts live in the SAME database (account_db).
         Splitting debit+credit into 2 REST calls is artificial.
         Teaching Saga on the wrong use case teaches the wrong lesson.
```

### After (Atomic Transfer — ACCEPTED)
```
Payment ──REST──► Account.transfer(from, to, amount)

Account Service internally (ONE DB transaction):
  BEGIN;
    UPDATE accounts SET balance = balance - ? WHERE id = ? AND balance >= ?;
    -- if rows_affected = 0 → insufficient balance → ROLLBACK
    UPDATE accounts SET balance = balance + ? WHERE id = ?;
    INSERT INTO audit_log (...);
  COMMIT;

Payment's real job: idempotency check, outbox management, client response.
1 REST call. ACID. Nothing to compensate.
```

### Rationale
- Both accounts are in the same PostgreSQL database. PostgreSQL handles atomicity natively.
- Forcing 2 REST calls adds latency, complexity, and failure modes with no benefit.
- The `WHERE balance >= ?` clause prevents overdraft atomically — no race condition between read and write.

### Where Saga Belongs (Phase 2)
```
Loan disbursement — GENUINE Saga:
  Loan Service (loan_db) → Account Service (account_db)
  Two DIFFERENT databases. Distributed transaction required.

  Loan Service (Orchestrator):
    1. UPDATE loan SET status='DISBURSING'    (loan_db)
    2. POST /accounts/credit(borrower, amount) (account_db)
    3. If (2) fails → UPDATE loan SET status='FAILED' (compensate)
    4. UPDATE loan SET status='DISBURSED'      (loan_db)
```

### Consequences
- MVP has no Saga pattern. This simplifies Payment Service significantly.
- Phase 2 (Loan Service) will teach Saga on the **correct** use case — genuine cross-database distributed transaction.
- Account Service gains a `transfer` endpoint with atomic balance updates.

---

## ADR-006: Tech Stack

**Date:** 2026-07-18  
**Status:** ✅ Accepted

| Layer | Choice | Why |
|-------|--------|-----|
| Language | Java 17 | Industry standard for banking, Spring ecosystem |
| Framework | Spring Boot 3 | De facto microservices framework for Java |
| Database | PostgreSQL (per service) | Database-per-service pattern |
| Message Broker | Apache Kafka | Event-driven communication, outbox pattern |
| Service Discovery | Netflix Eureka | Dynamic service registration |
| Config | Spring Cloud Config | Centralized configuration |
| Circuit Breaker | Resilience4j | Fault tolerance |
| Tracing | Micrometer + Zipkin | Distributed tracing |
| Containerization | Docker + Docker Compose | Local development |
| API Docs | SpringDoc OpenAPI | Swagger UI for testing |

---

## ADR-007: Correlation IDs for Distributed Tracing

**Date:** 2026-07-18  
**Status:** ✅ Accepted

### Decision
Every incoming request gets a `X-Correlation-Id` header (UUID). This ID is passed through every inter-service call and included in ALL log statements. Zipkin uses it for trace visualization.

```java
// Gateway generates it if missing
if (request.getHeader("X-Correlation-Id") == null) {
    request.setHeader("X-Correlation-Id", UUID.randomUUID().toString());
}

// Every service's RestTemplate interceptor passes it forward
restTemplate.getInterceptors().add((request, body, execution) -> {
    String correlationId = MDC.get("correlationId");
    request.getHeaders().add("X-Correlation-Id", correlationId);
    return execution.execute(request, body);
});

// All logs include it via logback pattern
// %d{ISO8601} [%thread] %-5level [%X{correlationId}] %logger - %msg%n
// Output: 2026-07-18 10:30:00 [http-nio-8080] INFO [a1b2c3d4] PaymentService - Transfer initiated
```

---

## ADR-008: Outbox Concurrency — SKIP LOCKED

**Date:** 2026-07-18  
**Status:** ✅ Accepted

### Decision
All outbox pollers use `SELECT ... FOR UPDATE SKIP LOCKED` to safely run across multiple service instances.

```java
@Transactional
public List<OutboxEntry> lockPending(String eventType, int limit) {
    return entityManager.createNativeQuery("""
        SELECT * FROM outbox 
        WHERE status = 'PENDING' AND event_type = :type
        ORDER BY created_at 
        LIMIT :limit
        FOR UPDATE SKIP LOCKED
    """, OutboxEntry.class)
    .setParameter("type", eventType)
    .setParameter("limit", limit)
    .getResultList();
}
// Instance A locks rows [1,2,3], Instance B gets [4,5,6] — no overlap.
```

---

## Summary of Decisions

| ADR | Decision | Key Pattern |
|-----|----------|-------------|
| 001 | Payment ≠ Transaction (separate services) | Single Source of Truth |
| 002 | Batch sync via Outbox + 4-state machine + error recovery | Outbox Pattern |
| 003 | Outbox table: JSONB, status enum, retry_count, SKIP LOCKED | Reliable Messaging |
| 004 | Dual schedulers: 1s for notifications, 10s for ledger | Priority-based Polling |
| 005 | Atomic Account.transfer() — no Saga in MVP | ACID within boundary |
| 006 | Java 17 + Spring Boot 3 + Kafka + PostgreSQL | Industry Standard Stack |
| 007 | Correlation IDs across all service calls + logs | Distributed Tracing |
| 008 | `FOR UPDATE SKIP LOCKED` for outbox pollers | Multi-Instance Safety |
| 009 | Gateway Secret header — services reject direct access | Defense in Depth |

> ⏳ **Saga** deferred to Phase 2 with Loan Service (genuine cross-DB distributed transaction).

---

## ADR-009: Gateway Secret — Services Reject Direct Access

**Date:** 2026-08-05  
**Status:** ✅ Accepted

### Context
Every service listens on its own port (8081-8085). Controllers trust `X-User-Id` / `X-User-Role` headers without verifying they came from the Gateway. An attacker who discovers a service port can forge these headers and bypass authentication entirely.

### Decision
**Shared secret between Gateway and all services.** Gateway injects `X-Gateway-Secret` into every request after JWT validation. Each service has a `GatewayAuthFilter` that rejects requests missing the correct secret. Actuator health endpoints are exempted (Docker health checks).

### Flow
```
Client → Gateway (JWT check → injects X-Gateway-Secret) → Service (validates secret → trusts X-User-Id)
Client → Service directly (no X-Gateway-Secret) → 403 "Direct access not allowed"
```

### Password Validation
Additionally, registration now enforces: min 8 chars, 1 uppercase, 1 digit, 1 special character.

### Consequences
- Direct port access blocked. All traffic must go through Gateway.
- Gateway secret configurable via `GATEWAY_SECRET` env var (default for dev only).
- Adds one header per request (negligible overhead).

> **Reference:** See `SCOPE.md` for what we're building. See `FUTURE_SCOPE.md` for Phase 2-4 roadmap.
