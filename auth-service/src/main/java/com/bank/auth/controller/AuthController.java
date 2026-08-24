package com.bank.auth.controller;

import com.bank.auth.model.RefreshToken;
import com.bank.auth.model.TokenBlacklist;
import com.bank.auth.model.User;
import com.bank.auth.model.User.Role;
import com.bank.auth.model.User.UserStatus;
import com.bank.auth.repository.RefreshTokenRepository;
import com.bank.auth.repository.TokenBlacklistRepository;
import com.bank.auth.repository.UserRepository;
import com.bank.auth.security.JwtUtil;
import io.jsonwebtoken.Claims;
import org.springframework.http.ResponseEntity;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.web.bind.annotation.*;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;

@RestController
@RequestMapping("/api/auth")
public class AuthController {

    private final UserRepository userRepo;
    private final RefreshTokenRepository refreshRepo;
    private final TokenBlacklistRepository blacklistRepo;
    private final JwtUtil jwtUtil;
    private final BCryptPasswordEncoder encoder = new BCryptPasswordEncoder();

    public AuthController(UserRepository userRepo, RefreshTokenRepository refreshRepo,
                          TokenBlacklistRepository blacklistRepo, JwtUtil jwtUtil) {
        this.userRepo = userRepo;
        this.refreshRepo = refreshRepo;
        this.blacklistRepo = blacklistRepo;
        this.jwtUtil = jwtUtil;
    }

    // ─── Register ───
    @PostMapping("/register")
    public ResponseEntity<?> register(@RequestBody RegisterRequest req) {
        if (userRepo.existsByEmail(req.email)) {
            return ResponseEntity.badRequest().body(Map.of("error", "Email already registered"));
        }
        if (!isValidPassword(req.password)) {
            return ResponseEntity.badRequest().body(Map.of("error",
                "Password must be at least 8 characters with 1 uppercase, 1 digit, and 1 special character"));
        }
        Role role = req.role != null ? req.role : Role.RETAIL;
        if (role == Role.EMPLOYEE || role == Role.ADMIN || role == Role.AUDITOR) {
            return ResponseEntity.badRequest().body(Map.of("error", "Cannot self-register as " + role));
        }
        User user = new User(req.email, encoder.encode(req.password), role);
        userRepo.save(user);

        String token = jwtUtil.generateAccessToken(user.getId(), user.getRole().name());
        String refreshToken = jwtUtil.generateRefreshToken();
        refreshRepo.save(new RefreshToken(user.getId(), sha256(refreshToken),
            Instant.now().plusMillis(jwtUtil.getRefreshExpirationMs())));

        return ResponseEntity.ok(Map.of(
            "userId", user.getId(),
            "token", token,
            "refreshToken", refreshToken,
            "expiresIn", 3600
        ));
    }

    // ─── Login ───
    @PostMapping("/login")
    public ResponseEntity<?> login(@RequestBody LoginRequest req) {
        User user = userRepo.findByEmail(req.email)
            .orElse(null);
        if (user == null || !encoder.matches(req.password, user.getPasswordHash())) {
            return ResponseEntity.status(401).body(Map.of("error", "Invalid credentials"));
        }
        if (user.getStatus() == UserStatus.LOCKED) {
            return ResponseEntity.status(403).body(Map.of("error", "Account locked"));
        }

        String token = jwtUtil.generateAccessToken(user.getId(), user.getRole().name());
        String refreshToken = jwtUtil.generateRefreshToken();
        refreshRepo.save(new RefreshToken(user.getId(), sha256(refreshToken),
            Instant.now().plusMillis(jwtUtil.getRefreshExpirationMs())));

        return ResponseEntity.ok(Map.of(
            "userId", user.getId(),
            "token", token,
            "refreshToken", refreshToken,
            "expiresIn", 3600
        ));
    }

    // ─── Logout ───
    @PostMapping("/logout")
    public ResponseEntity<?> logout(@RequestHeader("Authorization") String authHeader) {
        String token = authHeader.replace("Bearer ", "");
        try {
            Claims claims = jwtUtil.parseToken(token);
            blacklistRepo.save(new TokenBlacklist(claims.getId(), claims.getExpiration().toInstant()));
            refreshRepo.revokeAllForUser(UUID.fromString(claims.getSubject()));
        } catch (Exception ignored) {}
        return ResponseEntity.ok(Map.of("message", "Logged out"));
    }

    // ─── Refresh Token ───
    @PostMapping("/refresh")
    public ResponseEntity<?> refresh(@RequestBody Map<String, String> body) {
        String rawToken = body.get("refreshToken");
        if (rawToken == null) return ResponseEntity.badRequest().body(Map.of("error", "Missing refreshToken"));

        RefreshToken stored = refreshRepo.findByTokenHash(sha256(rawToken)).orElse(null);
        if (stored == null || stored.isRevoked() || stored.getExpiresAt().isBefore(Instant.now())) {
            return ResponseEntity.status(401).body(Map.of("error", "Invalid or expired refresh token"));
        }

        // Rotate: revoke old, issue new
        stored.setRevoked(true);
        refreshRepo.save(stored);

        User user = userRepo.findById(stored.getUserId()).orElseThrow();
        String newToken = jwtUtil.generateAccessToken(user.getId(), user.getRole().name());
        String newRefresh = jwtUtil.generateRefreshToken();
        refreshRepo.save(new RefreshToken(user.getId(), sha256(newRefresh),
            Instant.now().plusMillis(jwtUtil.getRefreshExpirationMs())));

        return ResponseEntity.ok(Map.of("token", newToken, "refreshToken", newRefresh, "expiresIn", 3600));
    }

    // ─── Internal: Validate JWT (called by Gateway) ───
    @GetMapping("/validate")
    public ResponseEntity<?> validate(@RequestHeader("Authorization") String authHeader) {
        String token = authHeader.replace("Bearer ", "");
        try {
            Claims claims = jwtUtil.parseToken(token);
            if (blacklistRepo.existsByJti(claims.getId())) {
                return ResponseEntity.status(401).body(Map.of("error", "Token blacklisted"));
            }
            return ResponseEntity.ok(Map.of(
                "sub", claims.getSubject(),
                "role", claims.get("role"),
                "valid", true
            ));
        } catch (Exception e) {
            return ResponseEntity.status(401).body(Map.of("error", "Invalid token"));
        }
    }

    // ─── Get Current User (from JWT headers set by Gateway) ───
    @GetMapping("/me")
    public ResponseEntity<?> me(@RequestHeader("X-User-Id") UUID userId,
                                 @RequestHeader("X-User-Role") String role) {
        return userRepo.findById(userId)
            .map(u -> ResponseEntity.ok(Map.of(
                "userId", u.getId(), "email", u.getEmail(), "role", role
            )))
            .orElse(ResponseEntity.ok(Map.of(
                "userId", userId, "role", role
            )));
    }

    // ─── Internal: Get User ───
    @GetMapping("/users/{id}")
    public ResponseEntity<?> getUser(@PathVariable UUID id) {
        return userRepo.findById(id)
            .map(u -> ResponseEntity.ok(Map.of(
                "id", u.getId(), "email", u.getEmail(), "role", u.getRole().name(), "status", u.getStatus().name()
            )))
            .orElse(ResponseEntity.notFound().build());
    }

    // ─── Forgot Password (mock) ───
    @PostMapping("/forgot-password")
    public ResponseEntity<?> forgotPassword(@RequestBody Map<String, String> body) {
        // Always return 200 — don't leak email existence
        String email = body.get("email");
        userRepo.findByEmail(email).ifPresent(user -> {
            String resetToken = jwtUtil.generateAccessToken(user.getId(), "RESET");
            System.out.println("🔑 [MOCK EMAIL] Reset link for " + email + ": /reset-password?token=" + resetToken);
        });
        return ResponseEntity.ok(Map.of("message", "If the email exists, a reset link has been sent"));
    }

    // ─── Reset Password ───
    @PostMapping("/reset-password")
    public ResponseEntity<?> resetPassword(@RequestBody Map<String, String> body) {
        String token = body.get("token");
        String newPassword = body.get("newPassword");
        if (token == null || newPassword == null)
            return ResponseEntity.badRequest().body(Map.of("error", "token and newPassword required"));

        try {
            Claims claims = jwtUtil.parseToken(token);
            UUID userId = UUID.fromString(claims.getSubject());
            User user = userRepo.findById(userId).orElseThrow();
            user.setPasswordHash(encoder.encode(newPassword));
            user.setUpdatedAt(Instant.now());
            userRepo.save(user);
            refreshRepo.revokeAllForUser(userId);
            return ResponseEntity.ok(Map.of("message", "Password reset successful"));
        } catch (Exception e) {
            return ResponseEntity.badRequest().body(Map.of("error", "Invalid or expired reset token"));
        }
    }

    private String sha256(String input) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] hash = md.digest(input.getBytes(StandardCharsets.UTF_8));
            StringBuilder hex = new StringBuilder();
            for (byte b : hash) hex.append(String.format("%02x", b));
            return hex.toString();
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    private boolean isValidPassword(String password) {
        if (password == null || password.length() < 8) return false;
        boolean hasUpper = false, hasDigit = false, hasSpecial = false;
        for (char c : password.toCharArray()) {
            if (Character.isUpperCase(c)) hasUpper = true;
            else if (Character.isDigit(c)) hasDigit = true;
            else if (!Character.isLetterOrDigit(c)) hasSpecial = true;
        }
        return hasUpper && hasDigit && hasSpecial;
    }

    // ─── DTOs ───
    record RegisterRequest(String email, String password, Role role) {}
    record LoginRequest(String email, String password) {}
}
