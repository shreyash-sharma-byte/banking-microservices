package com.bank.payment.model;

import jakarta.persistence.*;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "outbox")
public class OutboxEntry {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY) private Long id;
    @Column(name = "event_id", nullable = false, unique = true) private UUID eventId;
    @Column(name = "aggregate_id", nullable = false) private String aggregateId;
    @Column(name = "event_type", nullable = false) private String eventType;
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(nullable = false, columnDefinition = "jsonb") private String payload;
    @Column(name = "occurred_at", nullable = false) private Instant occurredAt;
    @Column(nullable = false) private String status = "PENDING";  // PENDING, SENDING, PUBLISHED, ERROR
    @Column(name = "batch_id") private String batchId;
    @Column(name = "error_message") private String errorMessage;
    @Column(name = "retry_count") private int retryCount;
    @Column(name = "published_at") private Instant publishedAt;
    @Column(name = "created_at") private Instant createdAt = Instant.now();
    @Column(name = "updated_at") private Instant updatedAt = Instant.now();

    public OutboxEntry() {}
    public OutboxEntry(UUID eventId, String aggregateId, String eventType, String payload) {
        this.eventId = eventId; this.aggregateId = aggregateId;
        this.eventType = eventType; this.payload = payload; this.occurredAt = Instant.now();
    }
    public Long getId() { return id; }
    public UUID getEventId() { return eventId; }
    public String getEventType() { return eventType; }
    public String getStatus() { return status; }
    public void setStatus(String s) { this.status = s; }
    public void setBatchId(String id) { this.batchId = id; }
    public void setUpdatedAt(Instant t) { this.updatedAt = t; }
    public void setErrorMessage(String e) { this.errorMessage = e; }
    public void setRetryCount(int c) { this.retryCount = c; }
    public void setPublishedAt(Instant t) { this.publishedAt = t; }
    public String getPayload() { return payload; }
    public int getRetryCount() { return retryCount; }
    public Instant getUpdatedAt() { return updatedAt; }
    public String getBatchId() { return batchId; }
}
