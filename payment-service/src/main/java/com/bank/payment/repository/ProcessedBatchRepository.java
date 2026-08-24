package com.bank.payment.repository;

import com.bank.payment.model.ProcessedBatch;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.UUID;

public interface ProcessedBatchRepository extends JpaRepository<ProcessedBatch, UUID> {
}
