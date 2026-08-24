package com.bank.notification.model;

import jakarta.persistence.*;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "notifications")
public class Notification {
    @Id @GeneratedValue(strategy = GenerationType.UUID) private UUID id;
    @Column(name = "user_id", nullable = false) private UUID userId;
    @Column(name = "account_id") private UUID accountId;
    @Column(nullable = false) private String channel;  // SMS, EMAIL
    @Column(nullable = false) private String template;
    @Column(nullable = false, columnDefinition = "TEXT") private String message;
    @Column(name = "reference_id") private String referenceId;
    @Column(nullable = false) private String status = "SENT";
    @Column(name = "error_message") private String errorMessage;
    @Column(name = "correlation_id") private String correlationId;
    @Column(name = "created_at") private Instant createdAt = Instant.now();

    public Notification() {}
    public Notification(UUID userId, UUID accountId, String channel, String template, String message, String referenceId, String correlationId) {
        this.userId = userId; this.accountId = accountId; this.channel = channel;
        this.template = template; this.message = message; this.referenceId = referenceId; this.correlationId = correlationId;
    }
    public UUID getId() { return id; }
    public UUID getUserId() { return userId; }
    public String getChannel() { return channel; }
    public String getTemplate() { return template; }
    public String getMessage() { return message; }
    public String getStatus() { return status; }
    public Instant getCreatedAt() { return createdAt; }
}
