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
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;

import java.lang.reflect.Field;
import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

class AuthControllerTest {

    private static final String SECRET = "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef";
    private static final long ACCESS_EXP = 3_600_000L;
    private static final long REFRESH_EXP = 604_800_000L;

    private UserRepository userRepo;
    private RefreshTokenRepository refreshRepo;
    private TokenBlacklistRepository blacklistRepo;
    private JwtUtil jwtUtil;
    private AuthController controller;

    @BeforeEach
    void setUp() {
        userRepo = mock(UserRepository.class);
        refreshRepo = mock(RefreshTokenRepository.class);
        blacklistRepo = mock(TokenBlacklistRepository.class);
        jwtUtil = new JwtUtil(SECRET, ACCESS_EXP, REFRESH_EXP);
        controller = new AuthController(userRepo, refreshRepo, blacklistRepo, jwtUtil);
    }

    // ── helpers ──

    private static void assignId(User user) {
        try {
            Field f = User.class.getDeclaredField("id");
            f.setAccessible(true);
            f.set(user, UUID.randomUUID());
        } catch (ReflectiveOperationException e) {
            throw new RuntimeException(e);
        }
    }

    private User persistedUser(String email, String rawPassword, Role role) {
        User user = new User(email, new BCryptPasswordEncoder().encode(rawPassword), role);
        assignId(user);
        return user;
    }

    private void stubSaveAssignsId() {
        when(userRepo.save(any(User.class))).thenAnswer(inv -> {
            User u = inv.getArgument(0);
            assignId(u);
            return u;
        });
    }

    private static int status(ResponseEntity<?> res) {
        return res.getStatusCode().value();
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> body(ResponseEntity<?> res) {
        return (Map<String, Object>) res.getBody();
    }

    // ── Register ──

    @Test
    void register_duplicateEmail_returns400() {
        when(userRepo.existsByEmail("dup@example.com")).thenReturn(true);

        ResponseEntity<?> res = controller.register(
                new AuthController.RegisterRequest("dup@example.com", "Password1!", Role.RETAIL));

        assertEquals(400, status(res));
        assertEquals("Email already registered", body(res).get("error"));
    }

    @Test
    void register_weakPassword_returns400() {
        when(userRepo.existsByEmail("new@example.com")).thenReturn(false);

        ResponseEntity<?> res = controller.register(
                new AuthController.RegisterRequest("new@example.com", "short1!", null));

        assertEquals(400, status(res));
        assertTrue(body(res).get("error").toString().contains("at least 8 characters"));
    }

    @Test
    void register_selfRegisterAsAdmin_returns400() {
        when(userRepo.existsByEmail("new@example.com")).thenReturn(false);

        ResponseEntity<?> res = controller.register(
                new AuthController.RegisterRequest("new@example.com", "Password1!", Role.ADMIN));

        assertEquals(400, status(res));
        assertEquals("Cannot self-register as ADMIN", body(res).get("error"));
    }

    @Test
    void register_success_returnsTokensAndDefaultRole() {
        when(userRepo.existsByEmail("new@example.com")).thenReturn(false);
        stubSaveAssignsId();

        ResponseEntity<?> res = controller.register(
                new AuthController.RegisterRequest("new@example.com", "Password1!", null));

        assertEquals(200, status(res));
        Map<String, Object> body = body(res);
        UUID userId = (UUID) body.get("userId");
        assertNotNull(userId);
        assertEquals(3600, body.get("expiresIn"));
        assertNotNull(body.get("refreshToken"));

        String token = (String) body.get("token");
        Claims claims = jwtUtil.parseToken(token);
        assertEquals(userId.toString(), claims.getSubject());
        assertEquals("RETAIL", claims.get("role"));

        verify(refreshRepo).save(any(RefreshToken.class));
    }

    // ── Login ──

    @Test
    void login_wrongPassword_returns401() {
        when(userRepo.findByEmail("u@example.com"))
                .thenReturn(Optional.of(persistedUser("u@example.com", "Password1!", Role.RETAIL)));

        ResponseEntity<?> res = controller.login(
                new AuthController.LoginRequest("u@example.com", "WrongPass1!"));

        assertEquals(401, status(res));
        assertEquals("Invalid credentials", body(res).get("error"));
    }

    @Test
    void login_unknownEmail_returns401() {
        when(userRepo.findByEmail("ghost@example.com")).thenReturn(Optional.empty());

        ResponseEntity<?> res = controller.login(
                new AuthController.LoginRequest("ghost@example.com", "Password1!"));

        assertEquals(401, status(res));
        assertEquals("Invalid credentials", body(res).get("error"));
    }

    @Test
    void login_lockedAccount_returns403() {
        User user = persistedUser("u@example.com", "Password1!", Role.RETAIL);
        user.setStatus(UserStatus.LOCKED);
        when(userRepo.findByEmail("u@example.com")).thenReturn(Optional.of(user));

        ResponseEntity<?> res = controller.login(
                new AuthController.LoginRequest("u@example.com", "Password1!"));

        assertEquals(403, status(res));
        assertEquals("Account locked", body(res).get("error"));
    }

    @Test
    void login_success_returnsTokens() {
        User user = persistedUser("u@example.com", "Password1!", Role.RETAIL);
        when(userRepo.findByEmail("u@example.com")).thenReturn(Optional.of(user));

        ResponseEntity<?> res = controller.login(
                new AuthController.LoginRequest("u@example.com", "Password1!"));

        assertEquals(200, status(res));
        Map<String, Object> body = body(res);
        assertEquals(user.getId(), body.get("userId"));
        assertNotNull(body.get("token"));
        assertNotNull(body.get("refreshToken"));
        verify(refreshRepo).save(any(RefreshToken.class));
    }

    // ── Logout ──

    @Test
    void logout_blacklistsTokenAndRevokesRefresh() {
        UUID userId = UUID.randomUUID();
        String token = jwtUtil.generateAccessToken(userId, "RETAIL");

        ResponseEntity<?> res = controller.logout("Bearer " + token);

        assertEquals(200, status(res));
        ArgumentCaptor<TokenBlacklist> captor = ArgumentCaptor.forClass(TokenBlacklist.class);
        verify(blacklistRepo).save(captor.capture());
        assertEquals(jwtUtil.parseToken(token).getId(), captor.getValue().getJti());
        verify(refreshRepo).revokeAllForUser(userId);
    }

    @Test
    void logout_invalidToken_stillReturns200WithoutSideEffects() {
        ResponseEntity<?> res = controller.logout("Bearer not-a-jwt");

        assertEquals(200, status(res));
        verify(blacklistRepo, never()).save(any());
        verify(refreshRepo, never()).revokeAllForUser(any());
    }

    // ── Refresh ──

    @Test
    void refresh_missingToken_returns400() {
        ResponseEntity<?> res = controller.refresh(Map.of());
        assertEquals(400, status(res));
        assertEquals("Missing refreshToken", body(res).get("error"));
    }

    @Test
    void refresh_unknownToken_returns401() {
        when(refreshRepo.findByTokenHash(anyString())).thenReturn(Optional.empty());

        ResponseEntity<?> res = controller.refresh(Map.of("refreshToken", "unknown"));

        assertEquals(401, status(res));
        assertEquals("Invalid or expired refresh token", body(res).get("error"));
    }

    @Test
    void refresh_expiredToken_returns401() {
        RefreshToken stored = new RefreshToken(UUID.randomUUID(), "h", Instant.now().minusSeconds(60));
        when(refreshRepo.findByTokenHash(anyString())).thenReturn(Optional.of(stored));

        ResponseEntity<?> res = controller.refresh(Map.of("refreshToken", "expired"));

        assertEquals(401, status(res));
        assertEquals("Invalid or expired refresh token", body(res).get("error"));
    }

    @Test
    void refresh_rotation_revokesOldAndIssuesNew() {
        UUID userId = UUID.randomUUID();
        User user = persistedUser("u@example.com", "Password1!", Role.RETAIL);
        RefreshToken stored = new RefreshToken(userId, "some-hash", Instant.now().plusSeconds(3600));

        when(refreshRepo.findByTokenHash(anyString())).thenReturn(Optional.of(stored));
        when(userRepo.findById(userId)).thenReturn(Optional.of(user));

        ResponseEntity<?> res = controller.refresh(Map.of("refreshToken", "raw-token"));

        assertEquals(200, status(res));
        assertTrue(stored.isRevoked());
        Map<String, Object> body = body(res);
        assertNotNull(body.get("token"));
        assertNotNull(body.get("refreshToken"));
        verify(refreshRepo, times(2)).save(any(RefreshToken.class));
    }

    // ── Validate ──

    @Test
    void validate_validToken_returns200WithClaims() {
        UUID userId = UUID.randomUUID();
        String token = jwtUtil.generateAccessToken(userId, "ADMIN");
        when(blacklistRepo.existsByJti(anyString())).thenReturn(false);

        ResponseEntity<?> res = controller.validate("Bearer " + token);

        assertEquals(200, status(res));
        Map<String, Object> body = body(res);
        assertEquals(true, body.get("valid"));
        assertEquals(userId.toString(), body.get("sub"));
        assertEquals("ADMIN", body.get("role"));
    }

    @Test
    void validate_blacklistedToken_returns401() {
        String token = jwtUtil.generateAccessToken(UUID.randomUUID(), "RETAIL");
        when(blacklistRepo.existsByJti(anyString())).thenReturn(true);

        ResponseEntity<?> res = controller.validate("Bearer " + token);

        assertEquals(401, status(res));
        assertEquals("Token blacklisted", body(res).get("error"));
    }

    @Test
    void validate_invalidToken_returns401() {
        ResponseEntity<?> res = controller.validate("Bearer not-a-jwt");
        assertEquals(401, status(res));
        assertEquals("Invalid token", body(res).get("error"));
    }

    // ── Forgot password ──

    @Test
    void forgotPassword_unknownEmail_returns200() {
        when(userRepo.findByEmail("nobody@example.com")).thenReturn(Optional.empty());

        ResponseEntity<?> res = controller.forgotPassword(Map.of("email", "nobody@example.com"));

        assertEquals(200, status(res));
        assertEquals("If the email exists, a reset link has been sent", body(res).get("message"));
    }

    // ── Reset password ──

    @Test
    void resetPassword_missingFields_returns400() {
        ResponseEntity<?> res = controller.resetPassword(Map.of("token", "abc"));
        assertEquals(400, status(res));
        assertEquals("token and newPassword required", body(res).get("error"));
    }

    @Test
    void resetPassword_validToken_updatesPasswordAndRevokes() {
        UUID userId = UUID.randomUUID();
        User user = persistedUser("u@example.com", "OldPass1!", Role.RETAIL);
        String token = jwtUtil.generateAccessToken(userId, "RESET");
        when(userRepo.findById(userId)).thenReturn(Optional.of(user));

        ResponseEntity<?> res = controller.resetPassword(
                Map.of("token", token, "newPassword", "NewPass1!"));

        assertEquals(200, status(res));
        assertEquals("Password reset successful", body(res).get("message"));
        assertTrue(new BCryptPasswordEncoder().matches("NewPass1!", user.getPasswordHash()));
        verify(refreshRepo).revokeAllForUser(userId);
    }
}
