# Transaction Service — Inbound Requests & System Flows

## Kafka Consumer (Primary Function)

### Consume Ledger Batches
```
@KafkaListener(topics = "ledger-batches")
Consume: { batchId, entries: [{ eventId, accountId, entryType, amount, reference, remark, occurredAt, correlationId }] }

  1. Idempotency check:
     SELECT 1 FROM processed_batches WHERE batch_id=?
     If exists → log.warn("Duplicate batch {}", batchId)
                  publishConfirmation(batchId, "COMMITTED")  // idempotent — already committed
                  return

  2. Bulk INSERT:
     INSERT INTO unified_ledger (event_id, source_service, account_id, entry_type, amount, reference, occurred_at, correlation_id)
     VALUES (?, 'payment', ?, ?, ?, ?, ?, ?)
     ON CONFLICT (event_id, occurred_at) DO NOTHING
     // Batch insert all entries in one SQL statement

  3. On SUCCESS:
     INSERT processed_batches (batch_id, entry_count, status='COMMITTED')
     publishConfirmation(batchId, "COMMITTED")
     log.info("Batch {} committed: {} entries", batchId, entries.size())

  4. On FAILURE (DataAccessException):
     log.error("""
       ╔══════════════════════════════════╗
       ║  BATCH PROCESSING FAILED        ║
       ║  batch_id: {}                   ║
       ║  entries:  {}                   ║
       ║  error:    {}                   ║
       ╚══════════════════════════════════╝
     """, batchId, entries.size(), e.getMessage(), e)
     
     INSERT processed_batches (batch_id, entry_count=0, status='FAILED', error_message=e.getMessage())
     publishConfirmation(batchId, "FAILED", e.getMessage())
```

### Publish Confirmation (helper)
```
kafkaTemplate.send("ledger-confirm", {
  batchId: batchId,
  status: "COMMITTED" | "FAILED",
  error: errorMessage | null,
  timestamp: NOW()
})
```

## User Queries

### View Transaction History (by Account)
```
GET /api/transactions?accountId={id}&page=0&size=20
  → Verify accountId belongs to JWT.sub:
      Call Account GET /api/accounts/{accountId} (internal)
      owner_id must = JWT.sub (RETAIL/BUSINESS) OR role=EMPLOYEE/ADMIN/AUDITOR
      Mismatch → 403
  → SELECT * FROM unified_ledger
    WHERE account_id=?
    ORDER BY occurred_at DESC
    LIMIT ? OFFSET ?
  → Return { content: [...], page, size, totalElements }
```

### View Transaction History (Filtered)
```
GET /api/transactions?accountId={id}&type=DEBIT&minAmount=5000&startDate=2026-06-01&endDate=2026-06-30&page=0&size=20
  → Same ownership check as above
  → SELECT * FROM unified_ledger
    WHERE account_id=?
      AND entry_type=?          (if provided)
      AND amount >= ?           (if minAmount provided)
      AND amount <= ?           (if maxAmount provided)
      AND occurred_at >= ?      (if startDate provided)
      AND occurred_at <= ?      (if endDate provided)
    ORDER BY occurred_at DESC
    LIMIT ? OFFSET ?
  → Return paginated
```

### View Single Transaction
```
GET /api/transactions/{entryId}
  → SELECT * FROM unified_ledger WHERE id=?
  → Verify ownership (same as above using the entry's account_id)
  → Return entry
```

### View All Transactions (System-wide, EMPLOYEE/ADMIN/AUDITOR)
```
GET /api/transactions?page=0&size=50&startDate=...&endDate=...
  → Verify role IN (EMPLOYEE, ADMIN, AUDITOR)
  → SELECT * FROM unified_ledger
    WHERE occurred_at >= ? AND occurred_at <= ?   (if dates provided)
    ORDER BY occurred_at DESC
    LIMIT ? OFFSET ?
  → Return paginated
```

### Daily Summary (EMPLOYEE/ADMIN/AUDITOR)
```
GET /api/transactions/daily-summary?date=2026-07-23
  → Verify role IN (EMPLOYEE, ADMIN, AUDITOR)
  → SELECT entry_type, COUNT(*) as txn_count, SUM(amount) as total_volume
    FROM unified_ledger
    WHERE occurred_at::date = ?
    GROUP BY entry_type
  → Return {
      date,
      totalCount: sum of all counts,
      totalVolume: sum of all volumes,
      debits: { count, volume },
      credits: { count, volume }
    }
```

## Exposed Endpoints Summary
```
GET   /api/transactions                  (list, ?accountId & ?page & ?size & filters)
GET   /api/transactions/{entryId}        (single entry)
GET   /api/transactions/daily-summary    (aggregated, EMPLOYEE+)
```

## Internal System Tasks Summary
```
CONSUMER  ledger-batches   ← Kafka  (bulk INSERT, confirm back via ledger-confirm)
PUBLISHER ledger-confirm   → Kafka  (COMMITTED or FAILED per batch)
```

## Notes
- All SELECT queries benefit from monthly partitioning on `occurred_at`
- `FOR UPDATE SKIP LOCKED` NOT needed here — only one consumer group reads ledger-batches
- `ON CONFLICT (event_id, occurred_at) DO NOTHING` is the final guard against duplicates
- `processed_batches` check is the fast-path guard (avoids 500 conflict checks per batch)
