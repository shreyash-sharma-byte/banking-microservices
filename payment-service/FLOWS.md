# Payment Service — Inbound Requests & System Flows

## User Flows

### Single Transfer (RETAIL or BUSINESS)
```
POST /api/payments/transfer
Headers: Idempotency-Key: <UUID>, X-Correlation-Id: <UUID>
Body: { fromAccount, toAccount, amount, remark }

  → Extract JWT.sub, JWT.role from headers (X-User-Id, X-User-Role set by Gateway)
  → Check idempotency:
      SELECT id, status FROM payments WHERE idempotency_key=?
      If found → return cached { paymentId, status } (no reprocessing)
  → Verify fromAccount ownership:
      Call Account GET /api/accounts/{fromAccount} (internal)
      owner_id must = JWT.sub → else 403
  → Verify role: RETAIL → fromAccount must be SAVINGS or SALARY. BUSINESS → must be CURRENT.
  → Execute transfer:
      Call Account POST /api/accounts/transfer { fromAccount, toAccount, amount, correlationId }
      Response: { success, transactionId } or { success: false, reason }
      If failed → return 400 with reason
  → BEGIN DB TXN (payment_db):
      INSERT payments (idempotency_key, from_account, to_account, amount, remark, category='TRANSFER', status='COMPLETED')
      INSERT outbox (event_type='LedgerEntry', status='PENDING', payload={entryType:DEBIT, accountId:from, amount, reference:paymentId, remark})
      INSERT outbox (event_type='LedgerEntry', status='PENDING', payload={entryType:CREDIT, accountId:to, amount, reference:paymentId, remark})
      INSERT outbox (event_type='NotificationRequired', status='PENDING', payload={fromAccount, toAccount, amount, remark, category:'TRANSFER', paymentId})
      COMMIT
  → Return { paymentId, status: "COMPLETED", transferredAt }
```

### Bulk Transfer (BUSINESS only)
```
POST /api/payments/batch
Headers: Idempotency-Key, X-Correlation-Id
Body: { fromAccount, category, transfers: [{ toAccount, amount, remark }] }

  → Check idempotency (same as single)
  → Verify role = BUSINESS, fromAccount.owner_id = JWT.sub
  → Generate batchId = UUID
  → results = []
  → For each transfer in transfers:
      Call Account POST /api/accounts/transfer { fromAccount, toAccount, amount, correlationId }
      If success → add { toAccount, amount, status: "COMPLETED" } to results
      If failed → add { toAccount, amount, status: "FAILED", reason } to results
  → BEGIN DB TXN:
      For each COMPLETED result:
        INSERT payments (idempotency_key=UUID.randomUUID, from, to, amount, remark, category, batch_id=batchId, status='COMPLETED')
        INSERT outbox (event_type='LedgerEntry', status='PENDING', ...) × 2 per payment
        INSERT outbox (event_type='NotificationRequired', status='PENDING', ...) per payment
      COMMIT
  → Return { batchId, category, totalAmount, successful: N, failed: M, results }
```

### View Payment History
```
GET /api/payments?accountId={id}&page=0&size=20
  → Verify accountId belongs to JWT.sub (RETAIL/BUSINESS) OR role=EMPLOYEE/ADMIN/AUDITOR
  → SELECT payments WHERE from_account=? OR to_account=? ORDER BY created_at DESC
  → Return paginated list
```

### View Single Payment
```
GET /api/payments/{id}
  → SELECT payment WHERE id=?
  → Verify ownership (from_account or to_account owner = JWT.sub) OR role=EMPLOYEE/ADMIN/AUDITOR
  → Return payment details
```

## System Flows (Schedulers)

### Outbox Poller — Notifications (every 1 second)
```
Runner: @Scheduled(fixedRate = 1000)
  1. SELECT * FROM outbox 
     WHERE status='PENDING' AND event_type='NotificationRequired'
     ORDER BY created_at LIMIT 100
     FOR UPDATE SKIP LOCKED
  2. If empty → return
  3. UPDATE outbox SET status='SENDING', batch_id=UUID.randomUUID(), updated_at=NOW()
     WHERE id IN (:selectedIds)
  4. Build payload: { batchId, events: [ { eventId, payload } ] }
  5. kafkaTemplate.send("payment-events", payload)
  6. // No confirmation needed for notifications — fire and forget
     // Outbox entries stay SENDING (cleaned up by a separate reaper for old SENDING entries)
```

### Outbox Poller — Ledger Entries (every 10 seconds)
```
Runner: @Scheduled(fixedRate = 10000)
  1. SELECT * FROM outbox
     WHERE status='PENDING' AND event_type='LedgerEntry'
     ORDER BY created_at LIMIT 500
     FOR UPDATE SKIP LOCKED
  2. If empty → return
  3. batchId = UUID.randomUUID()
  4. UPDATE outbox SET status='SENDING', batch_id=batchId, updated_at=NOW()
     WHERE id IN (:selectedIds)
  5. Build batch payload:
     {
       batchId,
       entries: [
         { eventId, accountId, entryType, amount, reference, remark, occurredAt }
       ]
     }
  6. kafkaTemplate.send("ledger-batches", batchPayload)
```

### Kafka Consumer — Ledger Confirmations
```
@KafkaListener(topics = "ledger-confirm")
Consume: { batchId, status: "COMMITTED"|"FAILED", error? }

  If status = "COMMITTED":
    INSERT processed_batches (batch_id, status='COMMITTED') ON CONFLICT DO NOTHING
    UPDATE outbox SET status='PUBLISHED', published_at=NOW(), updated_at=NOW()
    WHERE batch_id=? AND status='SENDING'
    log.info("Batch {} confirmed: {} rows published", batchId, rowsUpdated)

  If status = "FAILED":
    INSERT processed_batches (batch_id, status='FAILED', error=?)
    UPDATE outbox SET status='ERROR', error_message=?, retry_count=retry_count+1, updated_at=NOW()
    WHERE batch_id=? AND status='SENDING'
    log.error("Batch {} FAILED: {} — {} rows marked ERROR", batchId, error, rowsUpdated)
```

### Outbox Retry (every 30 seconds)
```
Runner: @Scheduled(fixedRate = 30000)
  UPDATE outbox SET status='PENDING', updated_at=NOW()
  WHERE status='ERROR' AND retry_count < 3 AND updated_at < NOW() - INTERVAL '30 seconds'
  // These will be picked up by the next 10s poller
```

### Outbox SENDING Reaper (every 5 minutes)
```
Runner: @Scheduled(fixedRate = 300000)
  // Clean up notification outbox entries stuck in SENDING for > 5 minutes
  // (No confirmation comes for notifications — if they've been SENDING this long, Kafka likely dropped it)
  // For ledger entries: don't reap SENDING — wait for confirmation
  UPDATE outbox SET status='ERROR', error_message='SENDING timeout', updated_at=NOW()
  WHERE status='SENDING' AND event_type='NotificationRequired' AND updated_at < NOW() - INTERVAL '5 minutes'
```

### Outbox Cleanup (hourly)
```
Runner: @Scheduled(cron = "0 0 * * * *")
  DELETE FROM outbox WHERE status='PUBLISHED' AND published_at < NOW() - INTERVAL '1 hour'
```

## Exposed Endpoints Summary
```
POST   /api/payments/transfer         (single, RETAIL/BUSINESS)
POST   /api/payments/batch            (bulk, BUSINESS only)
GET    /api/payments/{id}             (view one)
GET    /api/payments                  (list by account, ?accountId=&page=&size=)
```

## Internal System Tasks Summary
```
SCHEDULER  every 1s     outbox notification poller → Kafka payment-events
SCHEDULER  every 10s    outbox ledger poller → Kafka ledger-batches
CONSUMER   ledger-confirm   ← Kafka (COMMITTED → outbox PUBLISHED, FAILED → outbox ERROR)
SCHEDULER  every 30s    retry ERROR → PENDING
SCHEDULER  every 5min   reap stuck SENDING notifications
SCHEDULER  hourly       delete old PUBLISHED outbox rows
```
