package com.bank.auth.controller;

import com.bank.auth.model.User;
import com.bank.auth.model.User.Role;
import com.bank.auth.model.User.UserStatus;
import com.bank.auth.repository.RefreshTokenRepository;
import com.bank.auth.repository.UserRepository;
import org.springframework.http.ResponseEntity;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.web.bind.annotation.*;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

@RestController
@RequestMapping("/api/admin")
public class AdminController {

    private final UserRepository userRepo;
    private final RefreshTokenRepository refreshRepo;
    private final BCryptPasswordEncoder encoder = new BCryptPasswordEncoder();

    public AdminController(UserRepository userRepo, RefreshTokenRepository refreshRepo) {
        this.userRepo = userRepo;
        this.refreshRepo = refreshRepo;
    }

    // Admin check is done by Gateway (role=ADMIN). This controller trusts that.

    @PostMapping("/users")
    public ResponseEntity<?> provisionUser(@RequestBody ProvisionRequest req) {
        if (userRepo.existsByEmail(req.email)) {
            return ResponseEntity.badRequest().body(Map.of("error", "Email already exists"));
        }
        if (req.role == Role.RETAIL || req.role == Role.BUSINESS) {
            return ResponseEntity.badRequest().body(Map.of("error", "Use /api/auth/register for RETAIL/BUSINESS"));
        }
        User user = new User(req.email, encoder.encode(req.password), req.role);
        userRepo.save(user);
        return ResponseEntity.ok(Map.of("userId", user.getId()));
    }

    @PatchMapping("/users/{id}/role")
    public ResponseEntity<?> changeRole(@PathVariable UUID id, @RequestBody Map<String, String> body) {
        Role newRole = Role.valueOf(body.get("role"));
        User user = userRepo.findById(id).orElseThrow();
        user.setRole(newRole);
        user.setUpdatedAt(Instant.now());
        userRepo.save(user);
        refreshRepo.revokeAllForUser(id);
        return ResponseEntity.ok(Map.of("message", "Role updated"));
    }

    @PatchMapping("/users/{id}/status")
    public ResponseEntity<?> changeStatus(@PathVariable UUID id, @RequestBody Map<String, String> body) {
        UserStatus newStatus = UserStatus.valueOf(body.get("status"));
        User user = userRepo.findById(id).orElseThrow();
        user.setStatus(newStatus);
        user.setUpdatedAt(Instant.now());
        userRepo.save(user);
        if (newStatus == UserStatus.INACTIVE || newStatus == UserStatus.LOCKED) {
            refreshRepo.revokeAllForUser(id);
        }
        return ResponseEntity.ok(Map.of("message", "Status updated"));
    }

    @GetMapping("/users")
    public ResponseEntity<?> listUsers() {
        return ResponseEntity.ok(userRepo.findAll());
    }

    record ProvisionRequest(String email, String password, Role role) {}
}
