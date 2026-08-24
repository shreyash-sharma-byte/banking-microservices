package com.bank.auth.security;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.JwtException;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

class JwtUtilTest {

    // >= 32 bytes required by HS256 (Keys.hmacShaKeyFor enforces 256-bit minimum)
    private static final String SECRET = "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef";
    private static final long ACCESS_EXP = 3_600_000L;      // 1h
    private static final long REFRESH_EXP = 604_800_000L;   // 7d

    private JwtUtil jwtUtil() {
        return new JwtUtil(SECRET, ACCESS_EXP, REFRESH_EXP);
    }

    @Test
    void accessToken_roundTrips_subjectRoleAndJti() {
        JwtUtil jwt = jwtUtil();
        UUID userId = UUID.randomUUID();
        String token = jwt.generateAccessToken(userId, "ADMIN");

        Claims claims = jwt.parseToken(token);

        assertEquals(userId.toString(), claims.getSubject());
        assertEquals("ADMIN", claims.get("role"));
        assertNotNull(claims.getId());
        assertFalse(claims.getId().isBlank());
    }

    @Test
    void accessTokens_areUnique() {
        JwtUtil jwt = jwtUtil();
        UUID userId = UUID.randomUUID();
        assertNotEquals(
                jwt.generateAccessToken(userId, "RETAIL"),
                jwt.generateAccessToken(userId, "RETAIL"));
    }

    @Test
    void expiredToken_isRejected() {
        JwtUtil jwt = new JwtUtil(SECRET, -60_000L, REFRESH_EXP);
        String token = jwt.generateAccessToken(UUID.randomUUID(), "RETAIL");
        assertThrows(JwtException.class, () -> jwt.parseToken(token));
    }

    @Test
    void tamperedToken_isRejected() {
        JwtUtil jwt = jwtUtil();
        String token = jwt.generateAccessToken(UUID.randomUUID(), "RETAIL");
        String tampered = token.substring(0, token.length() - 2) + "xx";
        assertThrows(JwtException.class, () -> jwt.parseToken(tampered));
    }

    @Test
    void refreshToken_isOpaqueAndUnique() {
        JwtUtil jwt = jwtUtil();
        String a = jwt.generateRefreshToken();
        String b = jwt.generateRefreshToken();
        assertNotNull(a);
        assertFalse(a.isBlank());
        assertNotEquals(a, b);
    }

    @Test
    void refreshExpiration_isExposed() {
        JwtUtil jwt = jwtUtil();
        assertEquals(REFRESH_EXP, jwt.getRefreshExpirationMs());
    }
}
