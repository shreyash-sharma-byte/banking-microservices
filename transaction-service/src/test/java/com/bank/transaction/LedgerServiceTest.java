package com.bank.transaction;

import com.bank.transaction.model.LedgerService;
import jakarta.persistence.EntityManager;
import jakarta.persistence.Query;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class LedgerServiceTest {

    @Mock private KafkaTemplate<String, Object> kafka;
    @Mock private EntityManager em;
    @Mock private Query query;

    private LedgerService service;

    @BeforeEach
    void setUp() {
        service = new LedgerService(kafka);
        ReflectionTestUtils.setField(service, "em", em);
        when(em.createNativeQuery(anyString())).thenReturn(query);
        when(query.setParameter(anyInt(), any())).thenReturn(query);
    }

    private static Map<String, Object> ledgerEntry(String eventId, String accountId, String type, String amount) {
        return Map.of(
                "eventId", eventId,
                "accountId", accountId,
                "entryType", type,
                "amount", amount,
                "reference", "ref-" + eventId,
                "occurredAt", "2024-01-01T00:00:00Z",
                "correlationId", "corr-" + eventId);
    }

    // ── Idempotency: already-processed batch ─────────────────────────────────

    @Test
    void consumeBatch_alreadyProcessed_sendsCommittedWithoutReinserting() {
        String batchId = "batch-1";
        when(query.getSingleResult()).thenReturn(1L);

        service.consumeBatch(Map.of("batchId", batchId));

        verify(query, never()).executeUpdate();
        ArgumentCaptor<Map<String, Object>> captor = ArgumentCaptor.forClass(Map.class);
        verify(kafka, times(1)).send(eq("ledger-confirm"), captor.capture());
        Map<String, Object> confirm = captor.getValue();
        assertEquals("COMMITTED", confirm.get("status"));
        assertEquals(batchId, confirm.get("batchId"));
        assertEquals("", confirm.get("error"));
    }

    // ── Fresh batch: inserts entries + commit row + COMMITTED confirm ────────

    @Test
    void consumeBatch_freshBatch_insertsEntriesAndCommitRowAndSendsCommitted() {
        String batchId = "batch-2";
        when(query.getSingleResult()).thenReturn(0L);
        when(query.executeUpdate()).thenReturn(1);

        service.consumeBatch(Map.of("batchId", batchId, "entries",
                List.of(ledgerEntry("e1", "acc-1", "DEBIT", "10.00"),
                        ledgerEntry("e2", "acc-2", "CREDIT", "10.00"))));

        // 2 ledger entries + 1 processed_batches COMMITTED row
        verify(query, times(3)).executeUpdate();
        ArgumentCaptor<Map<String, Object>> captor = ArgumentCaptor.forClass(Map.class);
        verify(kafka, times(1)).send(eq("ledger-confirm"), captor.capture());
        Map<String, Object> confirm = captor.getValue();
        assertEquals("COMMITTED", confirm.get("status"));
        assertEquals(batchId, confirm.get("batchId"));
        assertEquals("", confirm.get("error"));
    }

    // ── Insert failure: FAILED confirm with error ────────────────────────────

    @Test
    void consumeBatch_insertFailure_recordsFailedBatchAndSendsFailedConfirm() {
        String batchId = "batch-3";
        when(query.getSingleResult()).thenReturn(0L);
        when(query.executeUpdate())
                .thenThrow(new RuntimeException("DB down"))
                .thenReturn(1);

        service.consumeBatch(Map.of("batchId", batchId, "entries",
                List.of(ledgerEntry("e1", "acc-1", "DEBIT", "10.00"))));

        // entry insert (throws) + processed_batches FAILED row (succeeds)
        verify(query, times(2)).executeUpdate();
        ArgumentCaptor<Map<String, Object>> captor = ArgumentCaptor.forClass(Map.class);
        verify(kafka, times(1)).send(eq("ledger-confirm"), captor.capture());
        Map<String, Object> confirm = captor.getValue();
        assertEquals("FAILED", confirm.get("status"));
        assertEquals(batchId, confirm.get("batchId"));
        assertEquals("DB down", confirm.get("error"));
    }
}
