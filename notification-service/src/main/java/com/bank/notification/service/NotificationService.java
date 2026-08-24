package com.bank.notification.service;

import com.bank.notification.model.Notification;
import com.bank.notification.repository.NotificationRepository;
import org.springframework.http.ResponseEntity;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Map;
import java.util.UUID;

@Service
public class NotificationService {

    private final NotificationRepository repo;

    public NotificationService(NotificationRepository repo) {
        this.repo = repo;
    }

    @KafkaListener(topics = "payment-events")
    @Transactional
    public void handlePaymentEvent(Map<String, Object> event) {
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> events = (List<Map<String, Object>>) event.get("events");
        if (events == null) return;

        for (var e : events) {
            @SuppressWarnings("unchecked")
            Map<String, Object> p = (Map<String, Object>) e.get("payload");
            if (p == null) p = e;  // fallback if payload is flat

            String fromAccount = (String) p.get("fromAccount");
            String toAccount = (String) p.get("toAccount");
            String amount = p.get("amount") != null ? p.get("amount").toString() : "0";
            String remark = (String) p.getOrDefault("remark", "");
            String payId = (String) p.get("paymentId");

            // For demo: just log. No actual user lookup (YAGNI — mock)
            String debitMsg = String.format("₹%s debited from a/c ...%s. %s. Ref: %s",
                amount, fromAccount != null ? fromAccount.substring(Math.max(0, fromAccount.length() - 4)) : "????",
                remark, payId != null ? payId.substring(0, 8) : "");

            Notification n = new Notification(UUID.randomUUID(), null, "SMS", "TRANSFER_DEBIT", debitMsg, payId, null);
            repo.save(n);
            System.out.println("📱 SMS: " + debitMsg);

            String creditMsg = String.format("₹%s credited to a/c ...%s. %s. Ref: %s",
                amount, toAccount != null ? toAccount.substring(Math.max(0, toAccount.length() - 4)) : "????",
                remark, payId != null ? payId.substring(0, 8) : "");
            Notification n2 = new Notification(UUID.randomUUID(), null, "SMS", "TRANSFER_CREDIT", creditMsg, payId, null);
            repo.save(n2);
            System.out.println("📱 SMS: " + creditMsg);
        }
    }

    public ResponseEntity<?> listNotifications(UUID userId, int page, int size) {
        return ResponseEntity.ok(repo.findAll());  // YAGNI: pagination later
    }

    public ResponseEntity<?> triggerNotification(UUID userId, String channel, String template, String message) {
        Notification n = new Notification(userId, null, channel, template, message, null, null);
        repo.save(n);
        System.out.println("📱 Manual SMS to " + userId + ": " + message);
        return ResponseEntity.ok(Map.of("notificationId", n.getId(), "status", "SENT"));
    }
}
