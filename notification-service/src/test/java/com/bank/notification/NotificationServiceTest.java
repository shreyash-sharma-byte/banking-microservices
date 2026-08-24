package com.bank.notification;

import com.bank.notification.model.Notification;
import com.bank.notification.repository.NotificationRepository;
import com.bank.notification.service.NotificationService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class NotificationServiceTest {

    @Mock private NotificationRepository repo;

    private NotificationService service;

    @BeforeEach
    void setUp() {
        service = new NotificationService(repo);
    }

    private static Map<String, Object> event(String payloadKey, Map<String, Object> payload) {
        return Map.of("events", List.of(Map.of(payloadKey, payload)));
    }

    // ── Kafka payment-events → notification rows ─────────────────────────────

    @Test
    void handlePaymentEvent_savesDebitAndCreditNotifications() {
        Map<String, Object> payload = Map.of(
                "fromAccount", "1111222233334444",
                "toAccount", "5555666677778888",
                "amount", "500.00",
                "remark", "rent",
                "paymentId", "pay-12345678");

        service.handlePaymentEvent(event("payload", payload));

        ArgumentCaptor<Notification> captor = ArgumentCaptor.forClass(Notification.class);
        verify(repo, times(2)).save(captor.capture());
        List<Notification> saved = captor.getAllValues();

        Notification debit = saved.get(0);
        assertEquals("SMS", debit.getChannel());
        assertEquals("TRANSFER_DEBIT", debit.getTemplate());
        assertTrue(debit.getMessage().contains("₹500.00"));
        assertTrue(debit.getMessage().contains("4444"));
        assertTrue(debit.getMessage().contains("pay-1234"));

        Notification credit = saved.get(1);
        assertEquals("TRANSFER_CREDIT", credit.getTemplate());
        assertTrue(credit.getMessage().contains("8888"));
    }

    // ── null / empty event handling ──────────────────────────────────────────

    @Test
    void handlePaymentEvent_nullEvents_doesNothing() {
        Map<String, Object> event = new HashMap<>();
        event.put("events", null);

        service.handlePaymentEvent(event);

        verify(repo, never()).save(any());
    }

    @Test
    void handlePaymentEvent_nullFields_usesPlaceholders() {
        Map<String, Object> payload = new HashMap<>();
        payload.put("fromAccount", null);
        payload.put("toAccount", null);
        payload.put("amount", null);
        payload.put("paymentId", null);

        service.handlePaymentEvent(event("payload", payload));

        ArgumentCaptor<Notification> captor = ArgumentCaptor.forClass(Notification.class);
        verify(repo, times(2)).save(captor.capture());
        assertTrue(captor.getAllValues().get(0).getMessage().contains("₹0"));
        assertTrue(captor.getAllValues().get(0).getMessage().contains("????"));
        assertTrue(captor.getAllValues().get(1).getMessage().contains("₹0"));
    }

    @Test
    void handlePaymentEvent_flatPayloadWithoutPayloadKey_fallsBackToEventMap() {
        Map<String, Object> flat = Map.of(
                "fromAccount", "1111222233334444",
                "toAccount", "5555666677778888",
                "amount", "10",
                "paymentId", "pay-12345678");

        service.handlePaymentEvent(event("unexpectedKey", flat));

        verify(repo, times(2)).save(any(Notification.class));
    }

    @Test
    void handlePaymentEvent_multipleEvents_savesPerEvent() {
        Map<String, Object> e1 = Map.of("payload", Map.of(
                "fromAccount", "a1", "toAccount", "b1", "amount", "1", "paymentId", "pay-11111111"));
        Map<String, Object> e2 = Map.of("payload", Map.of(
                "fromAccount", "a2", "toAccount", "b2", "amount", "2", "paymentId", "pay-22222222"));

        service.handlePaymentEvent(Map.of("events", List.of(e1, e2)));

        verify(repo, times(4)).save(any(Notification.class));
    }

    // ── listNotifications ────────────────────────────────────────────────────

    @Test
    void listNotifications_returnsAllFromRepository() {
        when(repo.findAll()).thenReturn(List.of());

        ResponseEntity<?> resp = service.listNotifications(UUID.randomUUID(), 0, 20);

        assertEquals(HttpStatus.OK, resp.getStatusCode());
        verify(repo).findAll();
    }
}
