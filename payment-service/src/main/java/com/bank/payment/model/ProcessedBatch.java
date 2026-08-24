package com.bank.payment.model;

import jakarta.persistence.*;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "processed_batches")
public class ProcessedBatch {
    @Id @Column(name = "batch_id") private UUID batchId;
    @Column(nullable = false) private String status;
    private String error;
    @Column(name = "created_at") private Instant createdAt = Instant.now();

    public ProcessedBatch() {}
    public ProcessedBatch(UUID batchId, String status, String error) {
        this.batchId = batchId; this.status = status; this.error = error;
    }
    public UUID getBatchId() { return batchId; }
}
