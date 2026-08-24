package com.bank.notification.controller;

import com.bank.notification.service.NotificationService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;
import java.util.UUID;

@RestController
@RequestMapping("/api/notifications")
public class NotificationController {

    private final NotificationService service;

    public NotificationController(NotificationService service) {
        this.service = service;
    }

    @GetMapping
    public ResponseEntity<?> list(@RequestParam UUID userId,
                                   @RequestParam(defaultValue = "0") int page,
                                   @RequestParam(defaultValue = "20") int size) {
        return service.listNotifications(userId, page, size);
    }

    @PostMapping
    public ResponseEntity<?> trigger(@RequestBody Map<String, Object> body,
                                      @RequestHeader("X-User-Role") String role) {
        if (!List.of("EMPLOYEE", "ADMIN").contains(role))
            return ResponseEntity.status(403).body(Map.of("error", "Access denied"));

        UUID userId = UUID.fromString((String) body.get("userId"));
        String channel = (String) body.get("channel");
        String template = (String) body.get("template");
        String message = (String) body.get("message");
        return service.triggerNotification(userId, channel, template, message);
    }
}
