package com.bank.payment.controller;

import com.bank.payment.model.*;
import com.bank.payment.repository.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.*;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.client.RestTemplate;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.*;

@RestController
public class PaymentController {

    private final PaymentRepository paymentRepo;
    private final OutboxRepository outboxRepo;
    private final ProcessedBatchRepository batchRepo;
    private final KafkaTemplate<String, Object> kafka;
    private final RestTemplate rest;
    private final HttpHeaders accountHeaders;

    public PaymentController(PaymentRepository paymentRepo, OutboxRepository outboxRepo,
                             ProcessedBatchRepository batchRepo, KafkaTemplate<String, Object> kafka,
                             @Value("${app.gateway.secret}") String gatewaySecret) {
        this.paymentRepo = paymentRepo;
        this.outboxRepo = outboxRepo;
        this.batchRepo = batchRepo;
        this.kafka = kafka;
        this.rest = new RestTemplate();
        this.accountHeaders = new HttpHeaders();
        this.accountHeaders.set("X-Gateway-Secret", gatewaySecret);
        this.accountHeaders.setContentType(MediaType.APPLICATION_JSON);
    }

    // ═══════════ SINGLE TRANSFER ═══════════
    @PostMapping("/api/payments/transfer")
    @Transactional
    public ResponseEntity<?> transfer(@RequestBody Map<String, Object> body,
                                       @RequestHeader("X-User-Id") UUID userId,
                                       @RequestHeader(value = "Idempotency-Key", required = false) String idemKeyStr,
                                       @RequestHeader(value = "X-Correlation-Id", defaultValue = "") String corrId) {
        UUID idemKey = idemKeyStr != null ? UUID.fromString(idemKeyStr) : UUID.randomUUID();

        // Check idempotency
        var existing = paymentRepo.findByIdempotencyKey(idemKey);
        if (existing.isPresent())
            return ResponseEntity.ok(Map.of("paymentId", existing.get().getId(), "status", existing.get().getStatus()));

        UUID from = UUID.fromString((String) body.get("fromAccount"));
        UUID to = UUID.fromString((String) body.get("toAccount"));
        BigDecimal amount = new BigDecimal(body.get("amount").toString());
        String remark = (String) body.getOrDefault("remark", "");
        UUID paymentId = UUID.randomUUID();

        // Call Account Service (atomic transfer)
        Map<String, Object> transferReq = Map.of(
            "fromAccountId", from, "toAccountId", to, "amount", amount, "correlationId", corrId
        );
        ResponseEntity<Map> resp = rest.postForEntity(
            "http://localhost:8082/api/accounts/transfer",
            new HttpEntity<>(transferReq, accountHeaders), Map.class);
        if (!resp.getStatusCode().is2xxSuccessful() || !Boolean.TRUE.equals(resp.getBody().get("success")))
            return ResponseEntity.badRequest().body(Map.of("error", resp.getBody().getOrDefault("reason", "Transfer failed")));

        // Save payment + outbox entries
        Payment payment = new Payment(idemKey, from, to, amount, remark, "TRANSFER", null);
        paymentRepo.save(payment);

        String debitPayload = String.format("{\"entryType\":\"DEBIT\",\"accountId\":\"%s\",\"amount\":%s,\"reference\":\"%s\",\"remark\":\"%s\"}",
            from, amount, paymentId, remark);
        String creditPayload = String.format("{\"entryType\":\"CREDIT\",\"accountId\":\"%s\",\"amount\":%s,\"reference\":\"%s\",\"remark\":\"%s\"}",
            to, amount, paymentId, remark);
        String notifPayload = String.format("{\"fromAccount\":\"%s\",\"toAccount\":\"%s\",\"amount\":%s,\"remark\":\"%s\",\"category\":\"TRANSFER\",\"paymentId\":\"%s\"}",
            from, to, amount, remark, paymentId);

        outboxRepo.save(new OutboxEntry(UUID.randomUUID(), paymentId.toString(), "LedgerEntry", debitPayload));
        outboxRepo.save(new OutboxEntry(UUID.randomUUID(), paymentId.toString(), "LedgerEntry", creditPayload));
        outboxRepo.save(new OutboxEntry(UUID.randomUUID(), paymentId.toString(), "NotificationRequired", notifPayload));

        return ResponseEntity.ok(Map.of("paymentId", payment.getId(), "status", "COMPLETED"));
    }

    // ═══════════ BULK TRANSFER ═══════════
    @PostMapping("/api/payments/batch")
    @Transactional
    public ResponseEntity<?> batchTransfer(@RequestBody Map<String, Object> body,
                                            @RequestHeader("X-User-Id") UUID userId,
                                            @RequestHeader(value = "Idempotency-Key", required = false) String idemKeyStr) {
        UUID idemKey = idemKeyStr != null ? UUID.fromString(idemKeyStr) : UUID.randomUUID();
        UUID fromAccount = UUID.fromString((String) body.get("fromAccount"));
        String category = (String) body.getOrDefault("category", "GENERAL");
        UUID batchId = UUID.randomUUID();

        var existing = paymentRepo.findByIdempotencyKey(idemKey);
        if (existing.isPresent()) return ResponseEntity.ok(Map.of("batchId", batchId, "message", "Already processed"));

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> transfers = (List<Map<String, Object>>) body.get("transfers");
        List<Map<String, Object>> results = new ArrayList<>();

        for (var t : transfers) {
            try {
                Map<String, Object> req = Map.of("fromAccountId", fromAccount,
                    "toAccountId", UUID.fromString((String) t.get("toAccount")),
                    "amount", new BigDecimal(t.get("amount").toString()), "correlationId", batchId.toString());
                ResponseEntity<Map> resp = rest.postForEntity(
                    "http://localhost:8082/api/accounts/transfer",
                    new HttpEntity<>(req, accountHeaders), Map.class);
                if (resp.getStatusCode().is2xxSuccessful() && Boolean.TRUE.equals(resp.getBody().get("success"))) {
                    results.add(Map.of("toAccount", t.get("toAccount"), "amount", t.get("amount"), "status", "COMPLETED"));
                } else {
                    results.add(Map.of("toAccount", t.get("toAccount"), "amount", t.get("amount"),
                        "status", "FAILED", "reason", resp.getBody().getOrDefault("reason", "Unknown")));
                }
            } catch (Exception e) {
                results.add(Map.of("toAccount", t.get("toAccount"), "amount", t.get("amount"), "status", "FAILED", "reason", e.getMessage()));
            }
        }

        // Save only successful payments
        for (int i = 0; i < transfers.size(); i++) {
            if ("COMPLETED".equals(results.get(i).get("status"))) {
                var t = transfers.get(i);
                UUID to = UUID.fromString((String) t.get("toAccount"));
                BigDecimal amt = new BigDecimal(t.get("amount").toString());
                String remark = (String) t.getOrDefault("remark", "");
                Payment p = new Payment(idemKey, fromAccount, to, amt, remark, category, batchId);
                paymentRepo.save(p);

                String dp = String.format("{\"entryType\":\"DEBIT\",\"accountId\":\"%s\",\"amount\":%s,\"reference\":\"%s\",\"remark\":\"%s\"}", fromAccount, amt, p.getId(), remark);
                String cp = String.format("{\"entryType\":\"CREDIT\",\"accountId\":\"%s\",\"amount\":%s,\"reference\":\"%s\",\"remark\":\"%s\"}", to, amt, p.getId(), remark);
                String np = String.format("{\"fromAccount\":\"%s\",\"toAccount\":\"%s\",\"amount\":%s,\"remark\":\"%s\",\"category\":\"%s\",\"paymentId\":\"%s\"}", fromAccount, to, amt, remark, category, p.getId());
                outboxRepo.save(new OutboxEntry(UUID.randomUUID(), p.getId().toString(), "LedgerEntry", dp));
                outboxRepo.save(new OutboxEntry(UUID.randomUUID(), p.getId().toString(), "LedgerEntry", cp));
                outboxRepo.save(new OutboxEntry(UUID.randomUUID(), p.getId().toString(), "NotificationRequired", np));
            }
        }

        long success = results.stream().filter(r -> "COMPLETED".equals(r.get("status"))).count();
        return ResponseEntity.ok(Map.of("batchId", batchId, "category", category, "results", results, "successful", success));
    }

    // ═══════════ SYSTEM: OUTBOX POLLERS ═══════════

    @Scheduled(fixedRateString = "${app.outbox.notification-poll-ms:1000}")
    @Transactional
    public void pollNotifications() {
        List<OutboxEntry> entries = outboxRepo.findPendingByTypeForUpdate("NotificationRequired");
        if (entries.isEmpty()) return;
        String batchId = UUID.randomUUID().toString();
        Instant now = Instant.now();
        outboxRepo.markSending(entries.stream().map(OutboxEntry::getId).toList(), batchId, now);
        kafka.send("payment-events", Map.of("batchId", batchId, "events",
            entries.stream().map(e -> Map.of("eventId", e.getEventId().toString(), "payload", e.getPayload())).toList()));
    }

    @Scheduled(fixedRateString = "${app.outbox.ledger-poll-ms:10000}")
    @Transactional
    public void pollLedgerEntries() {
        List<OutboxEntry> entries = outboxRepo.findPendingByTypeForUpdate("LedgerEntry");
        if (entries.isEmpty()) return;
        String batchId = UUID.randomUUID().toString();
        Instant now = Instant.now();
        outboxRepo.markSending(entries.stream().map(OutboxEntry::getId).toList(), batchId, now);

        List<Map<String, Object>> ledgerEntries = new ArrayList<>();
        for (var e : entries) {
            // Parse JSON payload (simplified — in real code use Jackson)
            String payload = e.getPayload();
            ledgerEntries.add(parseLedgerPayload(payload, e.getEventId().toString()));
        }
        kafka.send("ledger-batches", Map.of("batchId", batchId, "entries", ledgerEntries));
    }

    @Scheduled(fixedRateString = "${app.outbox.retry-poll-ms:30000}")
    @Transactional
    public void retryErrors() {
        Instant now = Instant.now();
        int reset = outboxRepo.resetErrorsForRetry(now, now.minusSeconds(30));
        if (reset > 0) System.out.println("♻ Retrying " + reset + " outbox entries");
    }

    @Scheduled(fixedRateString = "${app.outbox.sending-reaper-ms:300000}")
    @Transactional
    public void reapStuckSending() {
        Instant now = Instant.now();
        int reaped = outboxRepo.reapStuckSending(now, now.minusSeconds(300));
        if (reaped > 0) System.out.println("⚠ Reaped " + reaped + " stuck SENDING notifications");
    }

    @Scheduled(cron = "0 0 * * * *")  // hourly
    @Transactional
    public void cleanupPublished() {
        int deleted = outboxRepo.deleteOldPublished(Instant.now().minusSeconds(3600));
        if (deleted > 0) System.out.println("🧹 Cleaned up " + deleted + " PUBLISHED outbox entries");
    }

    // ═══════════ KAFKA CONSUMER: ledger-confirm ═══════════
    @org.springframework.kafka.annotation.KafkaListener(topics = "ledger-confirm")
    @Transactional
    public void handleConfirmation(Map<String, Object> confirm) {
        String batchId = (String) confirm.get("batchId");
        String status = (String) confirm.get("status");
        String error = (String) confirm.getOrDefault("error", null);

        if (batchRepo.existsById(UUID.fromString(batchId))) {
            System.out.println("⚠ Duplicate confirmation for batch " + batchId);
            return;
        }

        if ("COMMITTED".equals(status)) {
            batchRepo.save(new ProcessedBatch(UUID.fromString(batchId), "COMMITTED", null));
            int rows = outboxRepo.markPublished(batchId, Instant.now());
            System.out.println("✅ Batch " + batchId + " confirmed: " + rows + " rows PUBLISHED");
        } else {
            batchRepo.save(new ProcessedBatch(UUID.fromString(batchId), "FAILED", error));
            int rows = outboxRepo.markError(batchId, error, Instant.now());
            System.err.println("🔴 Batch " + batchId + " FAILED: " + error + " — " + rows + " rows marked ERROR");
        }
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> parseLedgerPayload(String payload, String eventId) {
        // Minimal inline JSON parser — YAGNI (no Jackson dependency in this controller)
        String accountId = extractJsonValue(payload, "accountId");
        String entryType = extractJsonValue(payload, "entryType");
        String amount = extractJsonValue(payload, "amount");
        String reference = extractJsonValue(payload, "reference");
        String remark = extractJsonValue(payload, "remark");
        return Map.of("eventId", eventId, "accountId", accountId, "entryType", entryType,
            "amount", amount, "reference", reference, "remark", remark, "occurredAt", Instant.now().toString());
    }

    private String extractJsonValue(String json, String key) {
        int idx = json.indexOf("\"" + key + "\"");
        if (idx < 0) return "";
        int colon = json.indexOf(":", idx) + 1;
        // Skip whitespace
        while (colon < json.length() && json.charAt(colon) == ' ') colon++;
        if (colon >= json.length()) return "";
        char first = json.charAt(colon);
        if (first == '"') {
            // String value
            int end = json.indexOf("\"", colon + 1);
            return end > colon ? json.substring(colon + 1, end) : "";
        } else {
            // Number/boolean/null — read until comma, brace, or whitespace
            int end = colon;
            while (end < json.length() && json.charAt(end) != ',' && json.charAt(end) != '}' && json.charAt(end) != ' ' && json.charAt(end) != '\n') end++;
            return json.substring(colon, end).trim();
        }
    }
}
