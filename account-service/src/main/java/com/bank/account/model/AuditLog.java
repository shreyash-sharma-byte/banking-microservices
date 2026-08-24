package com.bank.account.model;

import jakarta.persistence.*;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "audit_log")
public class AuditLog {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY) private Long id;
    @Column(name = "account_id", nullable = false) private UUID accountId;
    @Column(nullable = false) private String action;
    private BigDecimal amount;
    @Column(name = "balance_after") private BigDecimal balanceAfter;
    private String reference;
    @Column(name = "performed_by") private UUID performedBy;
    @Column(name = "correlation_id") private String correlationId;
    @Column(name = "created_at") private Instant createdAt = Instant.now();

    public AuditLog() {}
    public AuditLog(UUID accountId, String action, BigDecimal amount, BigDecimal balanceAfter, UUID performedBy, String correlationId) {
        this.accountId = accountId; this.action = action; this.amount = amount;
        this.balanceAfter = balanceAfter; this.performedBy = performedBy; this.correlationId = correlationId;
    }
    public Long getId() { return id; }
    public UUID getAccountId() { return accountId; }
    public String getAction() { return action; }
    public BigDecimal getAmount() { return amount; }
    public BigDecimal getBalanceAfter() { return balanceAfter; }
    public UUID getPerformedBy() { return performedBy; }
    public String getCorrelationId() { return correlationId; }
}
