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
GET /api/transactions?accountId={id}&type=&minAmount=&maxAmount=&startDate=&endDate=&page=0&size=20
  → SELECT * FROM unified_ledger
    WHERE account_id=?          (if accountId provided)
      AND entry_type=?          (if type provided)
      AND amount >= ?           (if minAmount provided)
      AND amount <= ?           (if maxAmount provided)
      AND occurred_at >= ?      (if startDate provided)
      AND occurred_at <  ?      (endDate + 1 day)
    ORDER BY occurred_at DESC
    LIMIT ? OFFSET ?
  → Return a BARE JSON ARRAY of rows — NOT the { content, page, size,
    totalElements } envelope this document previously described:
      [ { id, eventId, sourceService, accountId, entryType, amount,
          reference, occurredAt, ingestedAt, correlationId }, ... ]
```

### Search Transactions (POST — filters in the body)
```
POST /api/transactions/search
Body: { accountId?, type?, minAmount?, maxAmount?, startDate?, endDate?, page=0, size=20 }
  → Same query and same response as the GET form above (both call buildResult()).
    The frontend prefers this variant so account ids never travel in URLs.
  → Return a BARE JSON ARRAY (identical shape to GET).
```

⚠️ **Known gap: ownership is NOT enforced.** Both endpoints filter
`unified_ledger` by `accountId` without checking that the caller owns that
account — the gateway forwards `X-User-Id` / `X-User-Role`, but this controller
ignores them, and `buildResult()` performs no account-service lookup. Any
authenticated user can therefore read any account's ledger by passing its id
(a classic IDOR). The GET section above used to *document* an ownership check
that the code has never performed. The fix is to resolve the account through
account-service and compare `owner_id`, granting EMPLOYEE/ADMIN/AUDITOR
unrestricted access, exactly as the other services do.

### View Transaction History (Filtered)
```
GET /api/transactions?accountId={id}&type=DEBIT&minAmount=5000&startDate=2026-06-01&endDate=2026-06-30&page=0&size=20
  → No ownership check — see the known gap above (applies to this form too)
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
POST  /api/transactions/search           (same as GET, filters in body — no ids in URL)
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
