package com.bank.transaction.model;

import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Service
public class LedgerService {

    @PersistenceContext
    private EntityManager em;

    private final KafkaTemplate<String, Object> kafka;

    public LedgerService(KafkaTemplate<String, Object> kafka) {
        this.kafka = kafka;
    }

    @KafkaListener(topics = "ledger-batches")
    @Transactional
    public void consumeBatch(Map<String, Object> batch) {
        String batchId = (String) batch.get("batchId");

        // Idempotency
        Long count = (Long) em.createNativeQuery(
            "SELECT COUNT(*) FROM processed_batches WHERE batch_id = CAST(? AS uuid)")
            .setParameter(1, batchId)
            .getSingleResult();
        if (count > 0) {
            sendConfirm(batchId, "COMMITTED");
            return;
        }

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> entries = (List<Map<String, Object>>) batch.get("entries");

        try {
            for (var e : entries) {
                em.createNativeQuery(
                    "INSERT INTO unified_ledger (event_id, source_service, account_id, entry_type, amount, reference, occurred_at, correlation_id) " +
                    "VALUES (CAST(? AS uuid), 'payment', CAST(? AS uuid), ?, CAST(? AS numeric), ?, CAST(? AS timestamp), ?) " +
                    "ON CONFLICT (event_id, occurred_at) DO NOTHING")
                    .setParameter(1, e.get("eventId"))
                    .setParameter(2, e.get("accountId"))
                    .setParameter(3, e.get("entryType"))
                    .setParameter(4, new BigDecimal(e.get("amount").toString()))
                    .setParameter(5, e.getOrDefault("reference", ""))
                    .setParameter(6, e.getOrDefault("occurredAt", Instant.now().toString()))
                    .setParameter(7, e.getOrDefault("correlationId", ""))
                    .executeUpdate();
            }

            em.createNativeQuery(
                "INSERT INTO processed_batches (batch_id, entry_count, status) VALUES (CAST(? AS uuid), ?, 'COMMITTED')")
                .setParameter(1, batchId)
                .setParameter(2, entries.size())
                .executeUpdate();

            sendConfirm(batchId, "COMMITTED");
            System.out.println("✅ Batch " + batchId + " committed: " + entries.size() + " entries");

        } catch (Exception ex) {
            System.err.println("🔴 Batch " + batchId + " FAILED: " + ex.getMessage());

            em.createNativeQuery(
                "INSERT INTO processed_batches (batch_id, entry_count, status, error_message) VALUES (CAST(? AS uuid), 0, 'FAILED', ?)")
                .setParameter(1, batchId)
                .setParameter(2, ex.getMessage())
                .executeUpdate();

            sendConfirm(batchId, "FAILED", ex.getMessage());
        }
    }

    private void sendConfirm(String batchId, String status) {
        sendConfirm(batchId, status, null);
    }

    private void sendConfirm(String batchId, String status, String error) {
        kafka.send("ledger-confirm", Map.of(
            "batchId", batchId, "status", status,
            "error", error != null ? error : "",
            "timestamp", Instant.now().toString()
        ));
    }
}
