package com.bank.payment;

import com.bank.payment.controller.PaymentController;
import com.bank.payment.model.OutboxEntry;
import com.bank.payment.model.Payment;
import com.bank.payment.model.ProcessedBatch;
import com.bank.payment.repository.OutboxRepository;
import com.bank.payment.repository.PaymentRepository;
import com.bank.payment.repository.ProcessedBatchRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.client.RestTemplate;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class PaymentControllerTest {

    private static final String ACCOUNT_TRANSFER_URL = "http://localhost:8082/api/accounts/transfer";

    @Mock private PaymentRepository paymentRepo;
    @Mock private OutboxRepository outboxRepo;
    @Mock private ProcessedBatchRepository batchRepo;
    @Mock private KafkaTemplate<String, Object> kafka;
    @Mock private RestTemplate restTemplate;

    private PaymentController controller;

    @BeforeEach
    void setUp() {
        controller = new PaymentController(paymentRepo, outboxRepo, batchRepo, kafka, "test-secret");
        ReflectionTestUtils.setField(controller, "rest", restTemplate);
    }

    private Map<String, Object> transferBody(String from, String to, String amount) {
        return Map.of("fromAccount", from, "toAccount", to, "amount", amount, "remark", "rent");
    }

    // ── Transfer: idempotency ────────────────────────────────────────────────

    @Test
    void transfer_duplicateIdempotencyKey_returnsExistingPaymentWithoutSideEffects() {
        UUID idemKey = UUID.randomUUID();
        UUID existingPaymentId = UUID.randomUUID();
        Payment existing = new Payment(idemKey, UUID.randomUUID(), UUID.randomUUID(),
                new BigDecimal("100.00"), "rent", "TRANSFER", null);
        ReflectionTestUtils.setField(existing, "id", existingPaymentId);
        when(paymentRepo.findByIdempotencyKey(idemKey)).thenReturn(Optional.of(existing));

        ResponseEntity<?> resp = controller.transfer(Map.of(), UUID.randomUUID(), idemKey.toString(), "");

        assertEquals(HttpStatus.OK, resp.getStatusCode());
        Map<?, ?> body = (Map<?, ?>) resp.getBody();
        assertEquals(existingPaymentId, body.get("paymentId"));
        assertEquals("COMPLETED", body.get("status"));
        verify(restTemplate, never()).postForEntity(anyString(), any(), any());
        verify(paymentRepo, never()).save(any());
        verify(outboxRepo, never()).save(any());
    }

    // ── Transfer: happy path ─────────────────────────────────────────────────

    @Test
    void transfer_newPayment_callsAccountService_savesPaymentAndThreeOutboxEntries() {
        UUID idemKey = UUID.randomUUID();
        String from = UUID.randomUUID().toString();
        String to = UUID.randomUUID().toString();
        when(paymentRepo.findByIdempotencyKey(idemKey)).thenReturn(Optional.empty());
        doReturn(ResponseEntity.ok(Map.of("success", true)))
                .when(restTemplate)
                .postForEntity(eq(ACCOUNT_TRANSFER_URL), any(HttpEntity.class), eq(Map.class));
        // Simulate JPA @GeneratedValue assigning the payment id on save.
        when(paymentRepo.save(any(Payment.class))).thenAnswer(inv -> {
            Payment p = inv.getArgument(0);
            ReflectionTestUtils.setField(p, "id", UUID.randomUUID());
            return p;
        });

        ResponseEntity<?> resp = controller.transfer(
                transferBody(from, to, "100.50"), UUID.randomUUID(), idemKey.toString(), "corr-1");

        assertEquals(HttpStatus.OK, resp.getStatusCode());
        Map<?, ?> body = (Map<?, ?>) resp.getBody();
        assertEquals("COMPLETED", body.get("status"));
        assertNotNull(body.get("paymentId"));
        verify(restTemplate).postForEntity(eq(ACCOUNT_TRANSFER_URL), any(HttpEntity.class), eq(Map.class));
        verify(paymentRepo).save(any(Payment.class));
        verify(outboxRepo, times(3)).save(any(OutboxEntry.class));
    }

    @Test
    void transfer_insufficientFunds_returnsBadRequest_savesNothing() {
        UUID idemKey = UUID.randomUUID();
        when(paymentRepo.findByIdempotencyKey(idemKey)).thenReturn(Optional.empty());
        doReturn(ResponseEntity.ok(Map.of("success", false, "reason", "Insufficient funds")))
                .when(restTemplate)
                .postForEntity(anyString(), any(HttpEntity.class), eq(Map.class));

        ResponseEntity<?> resp = controller.transfer(
                transferBody(UUID.randomUUID().toString(), UUID.randomUUID().toString(), "9999.00"),
                UUID.randomUUID(), idemKey.toString(), "");

        assertEquals(HttpStatus.BAD_REQUEST, resp.getStatusCode());
        Map<?, ?> body = (Map<?, ?>) resp.getBody();
        assertEquals("Insufficient funds", body.get("error"));
        verify(paymentRepo, never()).save(any());
        verify(outboxRepo, never()).save(any());
    }

    // ── Outbox notification poller ───────────────────────────────────────────

    @Test
    void pollNotifications_noPendingEntries_doesNothing() {
        when(outboxRepo.findPendingByTypeForUpdate("NotificationRequired")).thenReturn(List.of());

        controller.pollNotifications();

        verify(outboxRepo, never()).markSending(anyList(), anyString(), any());
        verify(kafka, never()).send(anyString(), any());
    }

    @Test
    void pollNotifications_pendingEntries_marksSendingAndSendsKafkaEvent() {
        OutboxEntry e1 = new OutboxEntry(UUID.randomUUID(), "pay-1", "NotificationRequired", "{\"x\":1}");
        OutboxEntry e2 = new OutboxEntry(UUID.randomUUID(), "pay-2", "NotificationRequired", "{\"x\":2}");
        when(outboxRepo.findPendingByTypeForUpdate("NotificationRequired")).thenReturn(List.of(e1, e2));

        controller.pollNotifications();

        verify(outboxRepo).markSending(anyList(), anyString(), any(Instant.class));
        verify(kafka).send(eq("payment-events"), any());
    }

    // ── ledger-confirm consumer (outbox completion) ──────────────────────────

    @Test
    void handleConfirmation_committed_recordsBatchAndMarksPublished() {
        String batchId = UUID.randomUUID().toString();
        when(batchRepo.existsById(any(UUID.class))).thenReturn(false);

        controller.handleConfirmation(Map.of("batchId", batchId, "status", "COMMITTED"));

        ArgumentCaptor<ProcessedBatch> captor = ArgumentCaptor.forClass(ProcessedBatch.class);
        verify(batchRepo).save(captor.capture());
        assertEquals(batchId, captor.getValue().getBatchId().toString());
        verify(outboxRepo).markPublished(eq(batchId), any(Instant.class));
    }

    @Test
    void handleConfirmation_duplicateBatch_isIgnored() {
        String batchId = UUID.randomUUID().toString();
        when(batchRepo.existsById(any(UUID.class))).thenReturn(true);

        controller.handleConfirmation(Map.of("batchId", batchId, "status", "COMMITTED"));

        verify(batchRepo, never()).save(any());
        verify(outboxRepo, never()).markPublished(anyString(), any());
    }

    @Test
    void handleConfirmation_failed_recordsBatchAndMarksError() {
        String batchId = UUID.randomUUID().toString();
        when(batchRepo.existsById(any(UUID.class))).thenReturn(false);

        controller.handleConfirmation(Map.of("batchId", batchId, "status", "FAILED", "error", "boom"));

        verify(batchRepo).save(any(ProcessedBatch.class));
        verify(outboxRepo).markError(eq(batchId), eq("boom"), any(Instant.class));
    }
}
