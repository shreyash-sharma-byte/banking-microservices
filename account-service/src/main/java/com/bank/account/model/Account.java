package com.bank.account.model;

import jakarta.persistence.*;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "accounts")
public class Account {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "owner_id", nullable = false)
    private UUID ownerId;

    @Column(name = "account_number", nullable = false, unique = true, length = 12)
    private String accountNumber;

    @Column(name = "account_type", nullable = false)
    private String accountType;  // SAVINGS, SALARY, CURRENT

    @Column(nullable = false)
    private String label;

    @Column(nullable = false)
    private BigDecimal balance = BigDecimal.ZERO;

    @Column(name = "employer_business_id")
    private UUID employerBusinessId;

    @Column(nullable = false)
    private String status = "ACTIVE";  // ACTIVE, FROZEN, CLOSED

    @Column(name = "created_at")
    private Instant createdAt = Instant.now();

    @Column(name = "updated_at")
    private Instant updatedAt = Instant.now();

    public Account() {}
    public Account(UUID ownerId, String accountNumber, String accountType, String label, BigDecimal balance) {
        this.ownerId = ownerId; this.accountNumber = accountNumber;
        this.accountType = accountType; this.label = label; this.balance = balance;
    }

    public UUID getId() { return id; }
    public UUID getOwnerId() { return ownerId; }
    public String getAccountNumber() { return accountNumber; }
    public String getAccountType() { return accountType; }
    public String getLabel() { return label; }
    public BigDecimal getBalance() { return balance; }
    public UUID getEmployerBusinessId() { return employerBusinessId; }
    public String getStatus() { return status; }
    public Instant getCreatedAt() { return createdAt; }

    public void setEmployerBusinessId(UUID id) { this.employerBusinessId = id; }
    public void setBalance(BigDecimal b) { this.balance = b; }
    public void setStatus(String s) { this.status = s; }
    public void setAccountType(String t) { this.accountType = t; }
    public void setUpdatedAt(Instant t) { this.updatedAt = t; }
}
