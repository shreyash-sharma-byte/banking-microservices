# Notification Service — Inbound Requests & System Flows

## Kafka Consumer (Primary Function)

### Consume Payment Events
```
@KafkaListener(topics = "payment-events")
Consume: { fromAccount, toAccount, amount, remark, category, paymentId, correlationId }

  1. Lookup sender details:
     Call Account GET /api/accounts/{fromAccount} (internal)
     → Get owner_id → Call Auth GET /api/auth/users/{ownerId}
     → Get phone, email, fullName

  2. Lookup receiver details:
     Same as above for toAccount

  3. Render DEBIT SMS for sender:
     Template: "₹{amount} debited from a/c {last4} to {receiverName}. {remark}. Txn: {paymentIdLast8}"
     INSERT notifications (user_id=sender.userId, account_id=fromAccount, channel=SMS, template=TRANSFER_DEBIT, message=rendered, reference_id=paymentId, status=SENT, correlation_id)
     log.info("📱 SMS to {}: {}", sender.phone, renderedMessage)

  4. Render CREDIT SMS for receiver:
     Template: "₹{amount} credited to a/c {last4} from {senderName}. {remark}. Txn: {paymentIdLast8}"
     INSERT notifications (user_id=receiver.userId, account_id=toAccount, channel=SMS, template=TRANSFER_CREDIT, message=rendered, reference_id=paymentId, status=SENT, correlation_id)
     log.info("📱 SMS to {}: {}", receiver.phone, renderedMessage)

  5. If sender = receiver (self-transfer): skip DEBIT SMS, send only CREDIT
```

## User Queries

### View My Notifications
```
GET /api/notifications?userId={id}&page=0&size=20
  → Verify userId = JWT.sub (RETAIL/BUSINESS) OR role=EMPLOYEE/ADMIN/AUDITOR
  → SELECT * FROM notifications WHERE user_id=? ORDER BY created_at DESC LIMIT ? OFFSET ?
  → Return { content: [...], page, size, totalElements }
```

## Employee Operations

### Trigger Notification (EMPLOYEE/ADMIN)
```
POST /api/notifications { userId, channel, template, message }
  → Verify role IN (EMPLOYEE, ADMIN)
  → INSERT notifications (user_id, channel, template, message, status=SENT)
  → log.info("📱 Manual SMS to userId={}: {}", userId, message)
  → Return { notificationId, status: "SENT" }
```

## Templates

```
TRANSFER_DEBIT:
  "₹{amount} debited from a/c xxxx{accountLast4} to {beneficiaryName}. {remark}. Ref: {refShort}"

TRANSFER_CREDIT:
  "₹{amount} credited to a/c xxxx{accountLast4} from {senderName}. {remark}. Ref: {refShort}"

BATCH_COMPLETE:
  "Batch payment of ₹{totalAmount} to {count} recipients completed. Batch: {refShort}"

PASSWORD_RESET:
  "Password reset link: {resetLink}. Valid for 15 minutes."

ACCOUNT_FROZEN:
  "A/c xxxx{accountLast4} frozen. Contact support. Ref: {refShort}"

ACCOUNT_UNFROZEN:
  "A/c xxxx{accountLast4} is now active."
```

## Phase 2: Real Delivery

Replace `log.info()` with:
- SMS → Twilio / MSG91 API call
- EMAIL → SendGrid / AWS SES API call
- On failure: UPDATE notifications SET status='FAILED', error_message=?
- On success: status stays SENT
- If API call fails → DON'T commit Kafka offset → Kafka redelivers
- After 5 redeliveries → Dead Letter Queue

## Exposed Endpoints Summary
```
GET    /api/notifications             (list, ?userId=&page=&size=)
POST   /api/notifications             (trigger, EMPLOYEE/ADMIN)
```

## Internal System Tasks Summary
```
CONSUMER  payment-events  ← Kafka  (render SMS, INSERT notifications, mock delivery)
```
