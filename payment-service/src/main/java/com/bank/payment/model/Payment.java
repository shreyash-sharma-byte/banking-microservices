package com.bank.payment.model;

import jakarta.persistence.*;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "payments")
public class Payment {
    @Id @GeneratedValue(strategy = GenerationType.UUID) private UUID id;
    @Column(name = "idempotency_key", nullable = false, unique = true) private UUID idempotencyKey;
    @Column(name = "from_account", nullable = false) private UUID fromAccount;
    @Column(name = "to_account", nullable = false) private UUID toAccount;
    @Column(nullable = false) private BigDecimal amount;
    private String remark;
    private String category = "TRANSFER";
    @Column(name = "batch_id") private UUID batchId;
    @Column(nullable = false) private String status = "COMPLETED";
    @Column(name = "created_at") private Instant createdAt = Instant.now();

    public Payment() {}
    public Payment(UUID idempotencyKey, UUID from, UUID to, BigDecimal amount, String remark, String category, UUID batchId) {
        this.idempotencyKey = idempotencyKey; this.fromAccount = from; this.toAccount = to;
        this.amount = amount; this.remark = remark; this.category = category; this.batchId = batchId;
    }
    public UUID getId() { return id; }
    public UUID getIdempotencyKey() { return idempotencyKey; }
    public UUID getFromAccount() { return fromAccount; }
    public UUID getToAccount() { return toAccount; }
    public BigDecimal getAmount() { return amount; }
    public String getRemark() { return remark; }
    public String getCategory() { return category; }
    public UUID getBatchId() { return batchId; }
    public String getStatus() { return status; }
    public Instant getCreatedAt() { return createdAt; }
    public void setStatus(String s) { this.status = s; }
}
