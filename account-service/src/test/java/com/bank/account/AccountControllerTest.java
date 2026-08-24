package com.bank.account;

import com.bank.account.controller.AccountController;
import com.bank.account.model.Account;
import com.bank.account.model.BusinessEmployee;
import com.bank.account.repository.AccountRepository;
import com.bank.account.repository.AuditLogRepository;
import com.bank.account.repository.BusinessEmployeeRepository;
import com.bank.account.repository.BusinessProfileRepository;
import com.bank.account.repository.RetailProfileRepository;
import jakarta.persistence.EntityManager;
import jakarta.persistence.Query;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.test.util.ReflectionTestUtils;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Pure unit tests for the account-service core banking rules.
 * No Spring context, no DB — all repositories and the EntityManager are mocked.
 */
@ExtendWith(MockitoExtension.class)
class AccountControllerTest {

    @Mock AccountRepository accountRepo;
    @Mock RetailProfileRepository retailRepo;
    @Mock BusinessProfileRepository businessRepo;
    @Mock BusinessEmployeeRepository employeeRepo;
    @Mock AuditLogRepository auditRepo;
    @Mock EntityManager em;
    @Mock Query debitQuery;
    @Mock Query creditQuery;

    private AccountController controller;

    private final UUID userId = UUID.randomUUID();
    private final UUID otherUser = UUID.randomUUID();
    private final UUID accountId = UUID.randomUUID();
    private final UUID bizId = UUID.randomUUID();
    private final UUID empUserId = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        controller = new AccountController(accountRepo, retailRepo, businessRepo, employeeRepo, auditRepo);
        ReflectionTestUtils.setField(controller, "em", em);
    }

    private Map<String, Object> createBody(String type, String label, String deposit) {
        Map<String, Object> body = new java.util.HashMap<>();
        body.put("accountType", type);
        body.put("label", label);
        body.put("initialDeposit", deposit);
        return body;
    }

    /** Assigns a generated id on save, mimicking JPA's @GeneratedValue in a pure unit test. */
    private void stubAccountIdOnSave() {
        when(accountRepo.save(any(Account.class))).thenAnswer(inv -> {
            Account a = inv.getArgument(0);
            ReflectionTestUtils.setField(a, "id", UUID.randomUUID());
            return a;
        });
    }

    // ──────────────────────────────────────────────────────────────
    // 1. createAccount — role/type validation
    // ──────────────────────────────────────────────────────────────

    @Test
    void retailCannotOpenCurrentAccount() {
        ResponseEntity<?> resp = controller.createAccount(
                createBody("CURRENT", "biz", "20000"), userId, "RETAIL");
        assertEquals(HttpStatus.BAD_REQUEST, resp.getStatusCode());
        assertTrue(resp.getBody().toString().contains("RETAIL can only open SAVINGS or SALARY"));
    }

    @Test
    void businessCannotOpenSavingsAccount() {
        ResponseEntity<?> resp = controller.createAccount(
                createBody("SAVINGS", "sav", "5000"), userId, "BUSINESS");
        assertEquals(HttpStatus.BAD_REQUEST, resp.getStatusCode());
        assertTrue(resp.getBody().toString().contains("BUSINESS can only open CURRENT"));
    }

    @Test
    void retailCanOpenSavingsAccount_withFormattedAccountNumber() {
        when(accountRepo.countByOwnerIdAndStatus(userId, "ACTIVE")).thenReturn(0L);
        when(accountRepo.existsByOwnerIdAndLabel(userId, "savings")).thenReturn(false);
        when(accountRepo.nextAccountNumber()).thenReturn(42L);
        stubAccountIdOnSave();

        ResponseEntity<?> resp = controller.createAccount(
                createBody("SAVINGS", "savings", "5000"), userId, "RETAIL");

        assertEquals(HttpStatus.OK, resp.getStatusCode());
        @SuppressWarnings("unchecked")
        Map<String, Object> body = (Map<String, Object>) resp.getBody();
        assertEquals("100200000042", body.get("accountNumber"));
        assertEquals("SAVINGS", body.get("accountType"));
        verify(accountRepo).save(any(Account.class));
    }

    @Test
    void businessCanOpenCurrentAccount() {
        when(accountRepo.existsByOwnerIdAndLabel(userId, "ops")).thenReturn(false);
        when(accountRepo.nextAccountNumber()).thenReturn(7L);
        stubAccountIdOnSave();

        ResponseEntity<?> resp = controller.createAccount(
                createBody("CURRENT", "ops", "20000"), userId, "BUSINESS");

        assertEquals(HttpStatus.OK, resp.getStatusCode());
        @SuppressWarnings("unchecked")
        Map<String, Object> body = (Map<String, Object>) resp.getBody();
        assertEquals("100200000007", body.get("accountNumber"));
        assertEquals("CURRENT", body.get("accountType"));
    }

    @Test
    void retailMaxThreeActiveAccountsRejected() {
        when(accountRepo.countByOwnerIdAndStatus(userId, "ACTIVE")).thenReturn(3L);

        ResponseEntity<?> resp = controller.createAccount(
                createBody("SAVINGS", "fourth", "5000"), userId, "RETAIL");

        assertEquals(HttpStatus.BAD_REQUEST, resp.getStatusCode());
        assertTrue(resp.getBody().toString().contains("Max 3 accounts"));
    }

    // ──────────────────────────────────────────────────────────────
    // 1b. createAccount — SALARY rules
    // ──────────────────────────────────────────────────────────────

    @Test
    void salaryWithoutEmployerInfoRejected() {
        when(accountRepo.countByOwnerIdAndStatus(userId, "ACTIVE")).thenReturn(0L);

        ResponseEntity<?> resp = controller.createAccount(
                createBody("SALARY", "salary", "0"), userId, "RETAIL");

        assertEquals(HttpStatus.BAD_REQUEST, resp.getStatusCode());
        assertTrue(resp.getBody().toString().contains("employerBusinessId or employerName required"));
    }

    @Test
    void salaryWithRegisteredEmployerButNotEmployeeRejected() {
        when(accountRepo.countByOwnerIdAndStatus(userId, "ACTIVE")).thenReturn(0L);
        when(employeeRepo.existsByBusinessIdAndEmployeeIdAndStatus(bizId, userId, "ACTIVE")).thenReturn(false);

        Map<String, Object> body = createBody("SALARY", "salary", "0");
        body.put("employerBusinessId", bizId.toString());

        ResponseEntity<?> resp = controller.createAccount(body, userId, "RETAIL");

        assertEquals(HttpStatus.BAD_REQUEST, resp.getStatusCode());
        assertTrue(resp.getBody().toString().contains("Not an employee of this business"));
    }

    @Test
    void salaryAlreadyExistsRejected() {
        when(accountRepo.countByOwnerIdAndStatus(userId, "ACTIVE")).thenReturn(0L);
        when(accountRepo.findByOwnerIdAndStatusIn(userId, List.of("ACTIVE")))
                .thenReturn(List.of(new Account(userId, "100200000001", "SALARY", "old-salary", BigDecimal.ZERO)));

        Map<String, Object> body = createBody("SALARY", "salary", "0");
        body.put("employerName", "ACME Corp");

        ResponseEntity<?> resp = controller.createAccount(body, userId, "RETAIL");

        assertEquals(HttpStatus.BAD_REQUEST, resp.getStatusCode());
        assertTrue(resp.getBody().toString().contains("Already have a salary account"));
    }

    @Test
    void salaryWithRegisteredEmployerAndActiveLinkSucceeds() {
        when(accountRepo.countByOwnerIdAndStatus(userId, "ACTIVE")).thenReturn(0L);
        when(employeeRepo.existsByBusinessIdAndEmployeeIdAndStatus(bizId, userId, "ACTIVE")).thenReturn(true);
        when(accountRepo.findByOwnerIdAndStatusIn(userId, List.of("ACTIVE"))).thenReturn(List.of());
        when(accountRepo.existsByOwnerIdAndLabel(userId, "salary")).thenReturn(false);
        when(accountRepo.nextAccountNumber()).thenReturn(100L);
        stubAccountIdOnSave();

        Map<String, Object> body = createBody("SALARY", "salary", "0");
        body.put("employerBusinessId", bizId.toString());

        ResponseEntity<?> resp = controller.createAccount(body, userId, "RETAIL");

        assertEquals(HttpStatus.OK, resp.getStatusCode());
        @SuppressWarnings("unchecked")
        Map<String, Object> r = (Map<String, Object>) resp.getBody();
        assertEquals("SALARY", r.get("accountType"));
    }

    // ──────────────────────────────────────────────────────────────
    // 1c. createAccount — min deposit + duplicate label
    // ──────────────────────────────────────────────────────────────

    @Test
    void savingsBelowMinDepositRejected() {
        when(accountRepo.countByOwnerIdAndStatus(userId, "ACTIVE")).thenReturn(0L);

        ResponseEntity<?> resp = controller.createAccount(
                createBody("SAVINGS", "sav", "500"), userId, "RETAIL");

        assertEquals(HttpStatus.BAD_REQUEST, resp.getStatusCode());
        assertTrue(resp.getBody().toString().contains("Min deposit: ₹1000"));
    }

    @Test
    void currentBelowMinDepositRejected() {
        ResponseEntity<?> resp = controller.createAccount(
                createBody("CURRENT", "cur", "9999"), userId, "BUSINESS");

        assertEquals(HttpStatus.BAD_REQUEST, resp.getStatusCode());
        assertTrue(resp.getBody().toString().contains("Min deposit: ₹10000"));
    }

    @Test
    void salaryZeroDepositAllowed() {
        when(accountRepo.countByOwnerIdAndStatus(userId, "ACTIVE")).thenReturn(0L);
        when(accountRepo.findByOwnerIdAndStatusIn(userId, List.of("ACTIVE"))).thenReturn(List.of());
        when(accountRepo.existsByOwnerIdAndLabel(userId, "salary")).thenReturn(false);
        when(accountRepo.nextAccountNumber()).thenReturn(1L);
        stubAccountIdOnSave();

        Map<String, Object> body = createBody("SALARY", "salary", "0");
        body.put("employerName", "Unregistered Ltd");

        ResponseEntity<?> resp = controller.createAccount(body, userId, "RETAIL");

        assertEquals(HttpStatus.OK, resp.getStatusCode());
    }

    @Test
    void duplicateLabelRejected() {
        when(accountRepo.countByOwnerIdAndStatus(userId, "ACTIVE")).thenReturn(0L);
        when(accountRepo.existsByOwnerIdAndLabel(userId, "main")).thenReturn(true);

        ResponseEntity<?> resp = controller.createAccount(
                createBody("SAVINGS", "main", "5000"), userId, "RETAIL");

        assertEquals(HttpStatus.BAD_REQUEST, resp.getStatusCode());
        assertTrue(resp.getBody().toString().contains("Label already used"));
    }

    // ──────────────────────────────────────────────────────────────
    // 2. transfer — atomic debit/credit via EntityManager
    // ──────────────────────────────────────────────────────────────

    @Test
    void transferInsufficientFundsReturns400() {
        when(em.createNativeQuery(anyString())).thenReturn(debitQuery);
        when(debitQuery.setParameter(anyInt(), any())).thenReturn(debitQuery);
        when(debitQuery.executeUpdate()).thenReturn(0);

        ResponseEntity<?> resp = controller.transfer(Map.of(
                "fromAccountId", accountId.toString(),
                "toAccountId", UUID.randomUUID().toString(),
                "amount", "100",
                "correlationId", "corr-1"));

        assertEquals(HttpStatus.BAD_REQUEST, resp.getStatusCode());
        @SuppressWarnings("unchecked")
        Map<String, Object> body = (Map<String, Object>) resp.getBody();
        assertFalse((Boolean) body.get("success"));
        assertEquals("INSUFFICIENT_FUNDS", body.get("reason"));
        verify(creditQuery, never()).executeUpdate();
    }

    @Test
    void transferCreditFailureThrowsRuntimeException() {
        when(em.createNativeQuery(anyString())).thenReturn(debitQuery, creditQuery);
        when(debitQuery.setParameter(anyInt(), any())).thenReturn(debitQuery);
        when(creditQuery.setParameter(anyInt(), any())).thenReturn(creditQuery);
        when(debitQuery.executeUpdate()).thenReturn(1);
        when(creditQuery.executeUpdate()).thenReturn(0);

        assertThrows(RuntimeException.class, () -> controller.transfer(Map.of(
                "fromAccountId", accountId.toString(),
                "toAccountId", UUID.randomUUID().toString(),
                "amount", "100",
                "correlationId", "corr-2")));
    }

    @Test
    void transferHappyPathDebitsCreditsAndAudits() {
        Account from = new Account(userId, "100200000001", "SAVINGS", "from", new BigDecimal("1000"));
        Account to = new Account(otherUser, "100200000002", "SAVINGS", "to", new BigDecimal("500"));
        UUID toId = UUID.randomUUID();

        when(em.createNativeQuery(anyString())).thenReturn(debitQuery, creditQuery);
        when(debitQuery.setParameter(anyInt(), any())).thenReturn(debitQuery);
        when(creditQuery.setParameter(anyInt(), any())).thenReturn(creditQuery);
        when(debitQuery.executeUpdate()).thenReturn(1);
        when(creditQuery.executeUpdate()).thenReturn(1);
        when(accountRepo.findById(accountId)).thenReturn(Optional.of(from));
        when(accountRepo.findById(toId)).thenReturn(Optional.of(to));

        ResponseEntity<?> resp = controller.transfer(Map.of(
                "fromAccountId", accountId.toString(),
                "toAccountId", toId.toString(),
                "amount", "100",
                "correlationId", "corr-3"));

        assertEquals(HttpStatus.OK, resp.getStatusCode());
        @SuppressWarnings("unchecked")
        Map<String, Object> body = (Map<String, Object>) resp.getBody();
        assertTrue((Boolean) body.get("success"));
        assertTrue(body.containsKey("transactionId"));
        verify(debitQuery).executeUpdate();
        verify(creditQuery).executeUpdate();
        verify(auditRepo, times(2)).save(any());
    }

    // ──────────────────────────────────────────────────────────────
    // 3. closeAccount rules
    // ──────────────────────────────────────────────────────────────

    @Test
    void closeAccountNonOwnerReturns403() {
        Account account = new Account(otherUser, "100200000001", "SAVINGS", "x", BigDecimal.ZERO);
        when(accountRepo.findById(accountId)).thenReturn(Optional.of(account));

        ResponseEntity<?> resp = controller.closeAccount(accountId, userId, "RETAIL");

        assertEquals(HttpStatus.FORBIDDEN, resp.getStatusCode());
        assertTrue(resp.getBody().toString().contains("Access denied"));
    }

    @Test
    void closeAccountWithBalanceReturns400() {
        Account account = new Account(userId, "100200000001", "SAVINGS", "x", new BigDecimal("100"));
        when(accountRepo.findById(accountId)).thenReturn(Optional.of(account));

        ResponseEntity<?> resp = controller.closeAccount(accountId, userId, "RETAIL");

        assertEquals(HttpStatus.BAD_REQUEST, resp.getStatusCode());
        assertTrue(resp.getBody().toString().contains("Transfer remaining balance first"));
    }

    @Test
    void closeLastRetailAccountReturns400() {
        Account account = new Account(userId, "100200000001", "SAVINGS", "x", BigDecimal.ZERO);
        when(accountRepo.findById(accountId)).thenReturn(Optional.of(account));
        when(accountRepo.countByOwnerIdAndStatus(userId, "ACTIVE")).thenReturn(1L);

        ResponseEntity<?> resp = controller.closeAccount(accountId, userId, "RETAIL");

        assertEquals(HttpStatus.BAD_REQUEST, resp.getStatusCode());
        assertTrue(resp.getBody().toString().contains("Cannot close last account"));
    }

    @Test
    void closeAccountHappyPathSetsClosed() {
        Account account = new Account(userId, "100200000001", "SAVINGS", "x", BigDecimal.ZERO);
        when(accountRepo.findById(accountId)).thenReturn(Optional.of(account));
        when(accountRepo.countByOwnerIdAndStatus(userId, "ACTIVE")).thenReturn(2L);

        ResponseEntity<?> resp = controller.closeAccount(accountId, userId, "RETAIL");

        assertEquals(HttpStatus.OK, resp.getStatusCode());
        assertEquals("CLOSED", account.getStatus());
        verify(accountRepo).save(account);
        verify(auditRepo).save(any());
    }

    // ──────────────────────────────────────────────────────────────
    // 4. getAccount / listAccounts ownership
    // ──────────────────────────────────────────────────────────────

    @Test
    void retailCannotFetchOtherUsersAccount() {
        Account account = new Account(otherUser, "100200000001", "SAVINGS", "x", BigDecimal.ZERO);
        when(accountRepo.findById(accountId)).thenReturn(Optional.of(account));

        ResponseEntity<?> resp = controller.getAccount(accountId, userId, "RETAIL");

        assertEquals(HttpStatus.FORBIDDEN, resp.getStatusCode());
        assertTrue(resp.getBody().toString().contains("Not your account"));
    }

    @Test
    void businessCannotFetchOtherUsersAccount() {
        Account account = new Account(otherUser, "100200000001", "CURRENT", "x", BigDecimal.ZERO);
        when(accountRepo.findById(accountId)).thenReturn(Optional.of(account));

        ResponseEntity<?> resp = controller.getAccount(accountId, userId, "BUSINESS");

        assertEquals(HttpStatus.FORBIDDEN, resp.getStatusCode());
        assertTrue(resp.getBody().toString().contains("Not your account"));
    }

    @Test
    void listAccountsOtherOwnerReturns403() {
        ResponseEntity<?> resp = controller.listAccounts(otherUser, userId, "RETAIL");

        assertEquals(HttpStatus.FORBIDDEN, resp.getStatusCode());
        assertTrue(resp.getBody().toString().contains("Access denied"));
        verify(accountRepo, never()).findByOwnerIdAndStatusIn(any(), any());
    }

    // ──────────────────────────────────────────────────────────────
    // 5. removeEmployee — SALARY → SAVINGS conversion
    // ──────────────────────────────────────────────────────────────

    @Test
    void removeEmployeeConvertsMatchingSalaryAccountToSavings() {
        UUID otherBizId = UUID.randomUUID();
        BusinessEmployee link = new BusinessEmployee(bizId, empUserId, "E001");
        Account salary = new Account(empUserId, "100200000001", "SALARY", "pay", BigDecimal.ZERO);
        salary.setEmployerBusinessId(bizId);
        Account otherSalary = new Account(empUserId, "100200000002", "SALARY", "other", BigDecimal.ZERO);
        otherSalary.setEmployerBusinessId(otherBizId);

        when(employeeRepo.findByBusinessIdAndEmployeeId(bizId, empUserId)).thenReturn(Optional.of(link));
        when(accountRepo.findByOwnerIdAndStatusIn(empUserId, List.of("ACTIVE")))
                .thenReturn(List.of(salary, otherSalary));

        ResponseEntity<?> resp = controller.removeEmployee(bizId, empUserId, bizId);

        assertEquals(HttpStatus.OK, resp.getStatusCode());
        @SuppressWarnings("unchecked")
        Map<String, Object> body = (Map<String, Object>) resp.getBody();
        assertEquals(1, body.get("convertedAccounts"));
        assertEquals("SAVINGS", salary.getAccountType());
        assertEquals("SALARY", otherSalary.getAccountType());
        verify(employeeRepo).save(link);
        assertEquals("INACTIVE", link.getStatus());
        verify(auditRepo).save(any());
    }

    // ──────────────────────────────────────────────────────────────
    // 6. changeStatus — EMPLOYEE/ADMIN only
    // ──────────────────────────────────────────────────────────────

    @Test
    void changeStatusRejectsNonEmployeeOrAdmin() {
        ResponseEntity<?> resp = controller.changeStatus(accountId, Map.of("status", "FROZEN"), userId, "RETAIL");

        assertEquals(HttpStatus.FORBIDDEN, resp.getStatusCode());
        assertTrue(resp.getBody().toString().contains("Access denied"));
        verify(accountRepo, never()).findById(any());
    }

    @Test
    void changeStatusEmployeeFreezesAccount() {
        Account account = new Account(userId, "100200000001", "SAVINGS", "x", BigDecimal.ZERO);
        when(accountRepo.findById(accountId)).thenReturn(Optional.of(account));

        ResponseEntity<?> resp = controller.changeStatus(accountId, Map.of("status", "FROZEN"), userId, "EMPLOYEE");

        assertEquals(HttpStatus.OK, resp.getStatusCode());
        assertEquals("FROZEN", account.getStatus());
        verify(accountRepo).save(account);
    }
}
