package com.bank.payment.repository;

import com.bank.payment.model.OutboxEntry;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import java.time.Instant;
import java.util.List;

public interface OutboxRepository extends JpaRepository<OutboxEntry, Long> {

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT o FROM OutboxEntry o WHERE o.status = 'PENDING' AND o.eventType = :eventType ORDER BY o.createdAt")
    List<OutboxEntry> findPendingByTypeForUpdate(String eventType);

    @Modifying
    @Query("UPDATE OutboxEntry o SET o.status = 'SENDING', o.batchId = :batchId, o.updatedAt = :now WHERE o.id IN :ids")
    void markSending(List<Long> ids, String batchId, Instant now);

    @Modifying
    @Query("UPDATE OutboxEntry o SET o.status = 'PUBLISHED', o.publishedAt = :now, o.updatedAt = :now WHERE o.batchId = :batchId AND o.status = 'SENDING'")
    int markPublished(String batchId, Instant now);

    @Modifying
    @Query("UPDATE OutboxEntry o SET o.status = 'ERROR', o.errorMessage = :error, o.retryCount = o.retryCount + 1, o.updatedAt = :now WHERE o.batchId = :batchId AND o.status = 'SENDING'")
    int markError(String batchId, String error, Instant now);

    @Modifying
    @Query("UPDATE OutboxEntry o SET o.status = 'PENDING', o.updatedAt = :now WHERE o.status = 'ERROR' AND o.retryCount < 3 AND o.updatedAt < :before")
    int resetErrorsForRetry(Instant now, Instant before);

    @Modifying
    @Query("DELETE FROM OutboxEntry o WHERE o.status = 'PUBLISHED' AND o.publishedAt < :before")
    int deleteOldPublished(Instant before);

    @Modifying
    @Query("UPDATE OutboxEntry o SET o.status = 'ERROR', o.errorMessage = 'SENDING timeout', o.updatedAt = :now WHERE o.status = 'SENDING' AND o.eventType = 'NotificationRequired' AND o.updatedAt < :before")
    int reapStuckSending(Instant now, Instant before);
}
