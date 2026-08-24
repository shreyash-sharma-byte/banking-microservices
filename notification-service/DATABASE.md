# 🔔 Notification Service — Database Design

> **Database:** `notification_db`  
> **Purpose:** Store notification records. Consumers listen to Kafka `payment-events`, generate SMS/email messages, and log them here. In MVP, SMS/email is mocked (console.log).

---

## ER Diagram (Logical)

```
┌──────────────────────────┐
│      notifications       │
├──────────────────────────┤
│ id              PK UUID  │
│ user_id         UUID     │────► auth_db.users.id
│ account_id      UUID     │────► account_db.accounts.id (optional)
│ type            VARCHAR  │◄── SMS | EMAIL | PUSH
│ channel         VARCHAR  │◄── SMS | EMAIL
│ template        VARCHAR  │◄── TRANSFER_DEBIT | TRANSFER_CREDIT | BATCH_COMPLETE | PASSWORD_RESET
│ message         TEXT     │◄── Rendered message
│ reference_id    VARCHAR  │◄── payment_id or txn_id that triggered this
│ status          VARCHAR  │◄── PENDING | SENT | FAILED
│ error_message   TEXT     │◄── Why it failed (Phase 2)
│ correlation_id  VARCHAR  │
│ created_at      TIMESTAMP│
└──────────────────────────┘
```

---

## Tables

### `notifications`

Every notification attempt gets one row. All channels (SMS, email, push) use the same table for simplicity.

| Column | Type | Constraints | Notes |
|--------|------|-------------|-------|
| `id` | UUID | PK | |
| `user_id` | UUID | NOT NULL | Logical FK → auth_db.users |
| `account_id` | UUID | | Logical FK → account_db.accounts. For transaction alerts. |
| `channel` | VARCHAR(10) | NOT NULL, CHECK | `SMS` or `EMAIL` |
| `template` | VARCHAR(50) | NOT NULL | Which template to use |
| `message` | TEXT | NOT NULL | Rendered final message |
| `reference_id` | VARCHAR(255) | | Payment ID or event ID that triggered this |
| `status` | VARCHAR(20) | DEFAULT 'SENT' | `SENT` or `FAILED` (MVP always SENT — mock) |
| `error_message` | TEXT | | Phase 2: actual delivery failure reason |
| `correlation_id` | VARCHAR(36) | | For distributed tracing |
| `created_at` | TIMESTAMP | DEFAULT NOW() | |

```sql
CREATE TABLE notifications (
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

CREATE INDEX idx_notif_user ON notifications(user_id, created_at DESC);
CREATE INDEX idx_notif_ref ON notifications(reference_id);
CREATE INDEX idx_notif_status ON notifications(status);
```

---

## Templates

| Template ID | Trigger | Message (SMS) |
|-------------|---------|---------------|
| `TRANSFER_DEBIT` | Money sent | `₹{amount} debited from a/c {accountLast4} to {beneficiaryName}. Txn ID: {ref}.` |
| `TRANSFER_CREDIT` | Money received | `₹{amount} credited to a/c {accountLast4} from {senderName}. Txn ID: {ref}.` |
| `BATCH_COMPLETE` | Bulk transfer done | `Batch payment of ₹{total} to {count} recipients completed. Batch ID: {ref}.` |
| `PASSWORD_RESET` | Forgot password | `Your password reset link: {link}. Valid for 15 minutes.` |
| `ACCOUNT_FROZEN` | Account frozen | `Your a/c {accountLast4} has been frozen. Contact support. Ref: {ref}.` |
| `ACCOUNT_UNFROZEN` | Account unfrozen | `Your a/c {accountLast4} is now active. Ref: {ref}.` |

---

## How the Kafka Consumer Works

```java
@KafkaListener(topics = "payment-events")
public void handlePaymentEvent(PaymentEvent event) {
    // event arrives within ~1 second of transfer
    
    // 1. Look up sender's user_id from Account Service
    User sender = accountClient.getAccountOwner(event.fromAccount);
    User receiver = accountClient.getAccountOwner(event.toAccount);
    
    // 2. Render SMS for sender (DEBIT alert)
    String senderMsg = templateEngine.render("TRANSFER_DEBIT", Map.of(
        "amount", event.amount,
        "accountLast4", last4(event.fromAccount),
        "beneficiaryName", receiver.fullName,
        "ref", event.paymentId
    ));
    
    // 3. Save + "send" (mock: log to console)
    notificationRepo.save(new Notification(
        sender.userId, event.fromAccount, "SMS", "TRANSFER_DEBIT", 
        senderMsg, event.paymentId, "SENT", event.correlationId
    ));
    log.info("📱 SMS to {}: {}", sender.phone, senderMsg);
    
    // 4. Same for receiver (CREDIT alert)
    String receiverMsg = templateEngine.render("TRANSFER_CREDIT", ...);
    notificationRepo.save(new Notification(receiver.userId, ...));
    log.info("📱 SMS to {}: {}", receiver.phone, receiverMsg);
    
    // 5. If Kafka offset commit fails → Kafka redelivers → idempotency via reference_id
    //    Check: notificationRepo.existsByReferenceIdAndTemplate(event.paymentId, "TRANSFER_DEBIT")
}
```

---

## Query Pattern

```sql
-- Priya: "Show my notifications, newest first"
SELECT * FROM notifications 
WHERE user_id = 'usr_abc' 
ORDER BY created_at DESC 
LIMIT 20;
```

---

## Cross-Service Relationships

```
Notification DB                     Auth DB
notifications(user_id)       ──►   users(id)

Notification DB                     Account DB
notifications(account_id)     ──►   accounts(id)

Notification DB ←──Kafka── Payment DB (outbox → payment-events)
```

---

## Phase 2: Real Delivery

In MVP, `status` is always `SENT` and we `log.info()`. Phase 2 replaces with:

| Channel | Provider | Retry |
|---------|----------|-------|
| SMS | Twilio / MSG91 | 3 retries with backoff |
| EMAIL | SendGrid / AWS SES | 3 retries |
| PUSH | Firebase | 2 retries |

Failed deliveries → `status = FAILED`, `error_message` populated. No auto-retry in the consumer — Kafka redelivers if the consumer doesn't ACK. Dead Letter Queue after 5 failures.
