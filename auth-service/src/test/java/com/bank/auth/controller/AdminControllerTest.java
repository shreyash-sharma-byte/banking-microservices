package com.bank.auth.controller;

import com.bank.auth.model.User;
import com.bank.auth.model.User.Role;
import com.bank.auth.model.User.UserStatus;
import com.bank.auth.repository.RefreshTokenRepository;
import com.bank.auth.repository.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;

import java.lang.reflect.Field;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

class AdminControllerTest {

    private UserRepository userRepo;
    private RefreshTokenRepository refreshRepo;
    private AdminController controller;

    @BeforeEach
    void setUp() {
        userRepo = mock(UserRepository.class);
        refreshRepo = mock(RefreshTokenRepository.class);
        controller = new AdminController(userRepo, refreshRepo);
    }

    private static void assignId(User user) {
        try {
            Field f = User.class.getDeclaredField("id");
            f.setAccessible(true);
            f.set(user, UUID.randomUUID());
        } catch (ReflectiveOperationException e) {
            throw new RuntimeException(e);
        }
    }

    private User user(String email, Role role) {
        User u = new User(email, new BCryptPasswordEncoder().encode("Password1!"), role);
        assignId(u);
        return u;
    }

    private static int status(ResponseEntity<?> res) {
        return res.getStatusCode().value();
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> body(ResponseEntity<?> res) {
        return (Map<String, Object>) res.getBody();
    }

    @Test
    void provisionUser_duplicateEmail_returns400() {
        when(userRepo.existsByEmail("emp@example.com")).thenReturn(true);

        ResponseEntity<?> res = controller.provisionUser(
                new AdminController.ProvisionRequest("emp@example.com", "Password1!", Role.EMPLOYEE));

        assertEquals(400, status(res));
        assertEquals("Email already exists", body(res).get("error"));
    }

    @Test
    void provisionUser_retailRole_returns400() {
        when(userRepo.existsByEmail("r@example.com")).thenReturn(false);

        ResponseEntity<?> res = controller.provisionUser(
                new AdminController.ProvisionRequest("r@example.com", "Password1!", Role.RETAIL));

        assertEquals(400, status(res));
        assertEquals("Use /api/auth/register for RETAIL/BUSINESS", body(res).get("error"));
    }

    @Test
    void provisionUser_success_createsWithGivenRole() {
        when(userRepo.existsByEmail(anyString())).thenReturn(false);
        when(userRepo.save(any(User.class))).thenAnswer(inv -> {
            User u = inv.getArgument(0);
            assignId(u);
            return u;
        });

        ResponseEntity<?> res = controller.provisionUser(
                new AdminController.ProvisionRequest("emp@example.com", "Password1!", Role.EMPLOYEE));

        assertEquals(200, status(res));
        assertNotNull(body(res).get("userId"));
        ArgumentCaptor<User> captor = ArgumentCaptor.forClass(User.class);
        verify(userRepo).save(captor.capture());
        assertEquals(Role.EMPLOYEE, captor.getValue().getRole());
        assertEquals("emp@example.com", captor.getValue().getEmail());
    }

    @Test
    void changeRole_updatesRoleAndRevokesSessions() {
        UUID id = UUID.randomUUID();
        User u = user("u@example.com", Role.RETAIL);
        when(userRepo.findById(id)).thenReturn(Optional.of(u));

        ResponseEntity<?> res = controller.changeRole(id, Map.of("role", "AUDITOR"));

        assertEquals(200, status(res));
        assertEquals(Role.AUDITOR, u.getRole());
        verify(refreshRepo).revokeAllForUser(id);
    }

    @Test
    void changeStatus_lockRevokesSessions() {
        UUID id = UUID.randomUUID();
        User u = user("u@example.com", Role.RETAIL);
        when(userRepo.findById(id)).thenReturn(Optional.of(u));

        ResponseEntity<?> res = controller.changeStatus(id, Map.of("status", "LOCKED"));

        assertEquals(200, status(res));
        assertEquals(UserStatus.LOCKED, u.getStatus());
        verify(refreshRepo).revokeAllForUser(id);
    }

    @Test
    void changeStatus_activeDoesNotRevoke() {
        UUID id = UUID.randomUUID();
        User u = user("u@example.com", Role.RETAIL);
        when(userRepo.findById(id)).thenReturn(Optional.of(u));

        controller.changeStatus(id, Map.of("status", "ACTIVE"));

        assertEquals(UserStatus.ACTIVE, u.getStatus());
        verify(refreshRepo, never()).revokeAllForUser(any());
    }

    @Test
    void listUsers_returnsAllUsers() {
        List<User> users = List.of(user("a@example.com", Role.RETAIL), user("b@example.com", Role.EMPLOYEE));
        when(userRepo.findAll()).thenReturn(users);

        ResponseEntity<?> res = controller.listUsers();

        assertEquals(200, status(res));
        assertEquals(users, res.getBody());
    }
}
