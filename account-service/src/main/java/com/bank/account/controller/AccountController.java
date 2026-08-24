package com.bank.account.controller;

import com.bank.account.model.*;
import com.bank.account.repository.*;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import org.springframework.http.ResponseEntity;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.*;

@RestController
public class AccountController {

    private final AccountRepository accountRepo;
    private final RetailProfileRepository retailRepo;
    private final BusinessProfileRepository businessRepo;
    private final BusinessEmployeeRepository employeeRepo;
    private final AuditLogRepository auditRepo;

    @PersistenceContext
    private EntityManager em;

    public AccountController(AccountRepository accountRepo, RetailProfileRepository retailRepo,
                             BusinessProfileRepository businessRepo, BusinessEmployeeRepository employeeRepo,
                             AuditLogRepository auditRepo) {
        this.accountRepo = accountRepo;
        this.retailRepo = retailRepo;
        this.businessRepo = businessRepo;
        this.employeeRepo = employeeRepo;
        this.auditRepo = auditRepo;
    }

    // ═══════════════ PROFILE ═══════════════

    @PostMapping("/api/accounts/profile")
    public ResponseEntity<?> createRetailProfile(@RequestBody Map<String, Object> body,
                                                  @RequestHeader("X-User-Id") UUID userId) {
        RetailProfile profile = new RetailProfile(userId,
            (String) body.get("fullName"), (String) body.get("phone"),
            LocalDate.parse((String) body.get("dateOfBirth")), (String) body.get("panNumber"));
        if (body.containsKey("aadhaarLast4")) profile.setAadhaarLast4((String) body.get("aadhaarLast4"));
        if (body.containsKey("address")) profile.setAddress((String) body.get("address"));
        retailRepo.save(profile);
        return ResponseEntity.ok(profile);
    }

    @GetMapping("/api/accounts/profile")
    public ResponseEntity<?> getRetailProfile(@RequestHeader("X-User-Id") UUID userId) {
        return retailRepo.findByUserId(userId)
            .map(ResponseEntity::ok)
            .orElse(ResponseEntity.notFound().build());
    }

    @PostMapping("/api/accounts/business-profile")
    public ResponseEntity<?> createBusinessProfile(@RequestBody BusinessProfile profile,
                                                    @RequestHeader("X-User-Id") UUID userId,
                                                    @RequestHeader("X-User-Role") String role) {
        if (!"BUSINESS".equals(role)) return ResponseEntity.status(403).body(Map.of("error", "BUSINESS role required"));
        profile.setUserId(userId);
        businessRepo.save(profile);
        return ResponseEntity.ok(profile);
    }

    @GetMapping("/api/accounts/business-profile")
    public ResponseEntity<?> getBusinessProfile(@RequestHeader("X-User-Id") UUID userId) {
        return businessRepo.findByUserId(userId)
            .map(ResponseEntity::ok)
            .orElse(ResponseEntity.notFound().build());
    }

    // ── List all registered businesses (for salary-account employer dropdown) ──
    @GetMapping("/api/accounts/businesses")
    public ResponseEntity<?> listBusinesses() {
        return ResponseEntity.ok(businessRepo.findAll().stream()
            .map(b -> Map.of("id", b.getId(), "companyName", b.getCompanyName()))
            .toList());
    }

    // ═══════════════ ACCOUNTS ═══════════════

    @PostMapping("/api/accounts")
    public ResponseEntity<?> createAccount(@RequestBody Map<String, Object> body,
                                            @RequestHeader("X-User-Id") UUID userId,
                                            @RequestHeader("X-User-Role") String role) {
        String accountType = (String) body.get("accountType");
        String label = (String) body.get("label");
        BigDecimal deposit = new BigDecimal(body.get("initialDeposit").toString());
        UUID employerBizId = body.containsKey("employerBusinessId") ?
            UUID.fromString((String) body.get("employerBusinessId")) : null;

        // Validate role + account type
        if ("RETAIL".equals(role) && !List.of("SAVINGS", "SALARY").contains(accountType))
            return ResponseEntity.badRequest().body(Map.of("error", "RETAIL can only open SAVINGS or SALARY"));
        if ("BUSINESS".equals(role) && !"CURRENT".equals(accountType))
            return ResponseEntity.badRequest().body(Map.of("error", "BUSINESS can only open CURRENT"));

        // Check max accounts (RETAIL: 3)
        if ("RETAIL".equals(role)) {
            long count = accountRepo.countByOwnerIdAndStatus(userId, "ACTIVE");
            if (count >= 3) return ResponseEntity.badRequest().body(Map.of("error", "Max 3 accounts"));
        }

        // SALARY specific: verify employer + employee link
        if ("SALARY".equals(accountType)) {
            String employerName = (String) body.get("employerName");
            if (employerBizId == null && employerName == null)
                return ResponseEntity.badRequest().body(Map.of("error", "employerBusinessId or employerName required"));
            // Registered company — validate employee link
            if (employerBizId != null) {
                if (!employeeRepo.existsByBusinessIdAndEmployeeIdAndStatus(employerBizId, userId, "ACTIVE"))
                    return ResponseEntity.badRequest().body(Map.of("error", "Not an employee of this business"));
            }
            // Unregistered company (employerName only) — skip validation, record for reference
            // Check existing SALARY account
            List<Account> existing = accountRepo.findByOwnerIdAndStatusIn(userId, List.of("ACTIVE"));
            if (existing.stream().anyMatch(a -> "SALARY".equals(a.getAccountType())))
                return ResponseEntity.badRequest().body(Map.of("error", "Already have a salary account"));
            if (deposit.compareTo(BigDecimal.ZERO) < 0)
                return ResponseEntity.badRequest().body(Map.of("error", "Min deposit: ₹0"));
        }

        // Min deposit check
        BigDecimal min = "CURRENT".equals(accountType) ? new BigDecimal("10000") : new BigDecimal("1000");
        if (!"SALARY".equals(accountType) && deposit.compareTo(min) < 0)
            return ResponseEntity.badRequest().body(Map.of("error", "Min deposit: ₹" + min));

        // Check label uniqueness
        if (accountRepo.existsByOwnerIdAndLabel(userId, label))
            return ResponseEntity.badRequest().body(Map.of("error", "Label already used"));

        // Generate account number
        String accNum = String.format("1002%08d", accountRepo.nextAccountNumber());

        Account account = new Account(userId, accNum, accountType, label, deposit);
        if (employerBizId != null) account.setEmployerBusinessId(employerBizId);
        accountRepo.save(account);

        auditRepo.save(new AuditLog(account.getId(), "CREATED", deposit, deposit, userId, null));

        return ResponseEntity.ok(Map.of(
            "accountId", account.getId(), "accountNumber", accNum,
            "accountType", accountType, "label", label, "balance", deposit
        ));
    }

    @GetMapping("/api/accounts/{id}")
    public ResponseEntity<?> getAccount(@PathVariable UUID id,
                                         @RequestHeader("X-User-Id") UUID userId,
                                         @RequestHeader("X-User-Role") String role) {
        Account account = accountRepo.findById(id).orElse(null);
        if (account == null) return ResponseEntity.notFound().build();
        // Own-data check for RETAIL/BUSINESS
        if (List.of("RETAIL", "BUSINESS").contains(role) && !account.getOwnerId().equals(userId))
            return ResponseEntity.status(403).body(Map.of("error", "Not your account"));
        return ResponseEntity.ok(account);
    }

    @GetMapping("/api/accounts/user/{ownerId}")
    public ResponseEntity<?> listAccounts(@PathVariable UUID ownerId,
                                           @RequestHeader("X-User-Id") UUID userId,
                                           @RequestHeader("X-User-Role") String role) {
        if (List.of("RETAIL", "BUSINESS").contains(role) && !ownerId.equals(userId))
            return ResponseEntity.status(403).body(Map.of("error", "Access denied"));
        return ResponseEntity.ok(accountRepo.findByOwnerIdAndStatusIn(ownerId, List.of("ACTIVE", "FROZEN")));
    }

    // ── Secure: get MY accounts via POST (no IDs in URL) ──
    @PostMapping("/api/accounts/me")
    public ResponseEntity<?> myAccounts(@RequestHeader("X-User-Id") UUID userId,
                                         @RequestHeader("X-User-Role") String role) {
        return ResponseEntity.ok(accountRepo.findByOwnerIdAndStatusIn(userId, List.of("ACTIVE", "FROZEN")));
    }

    // ── Compliant lookup: search by account number, label, or owner name ──
    @GetMapping("/api/accounts/lookup")
    public ResponseEntity<?> lookup(@RequestParam String q,
                                     @RequestHeader("X-User-Id") UUID userId) {
        if (q == null || q.length() < 2) return ResponseEntity.ok(List.of());
        return ResponseEntity.ok(accountRepo.searchByNumberOrLabel(q).stream()
            .map(a -> {
                String ownerName = retailRepo.findByUserId(a.getOwnerId())
                    .map(RetailProfile::getFullName)
                    .orElse(businessRepo.findByUserId(a.getOwnerId())
                        .map(BusinessProfile::getCompanyName)
                        .orElse(a.getLabel()));
                return Map.of(
                    "id", a.getId(),
                    "accountNumber", a.getAccountNumber(),
                    "label", a.getLabel(),
                    "accountType", a.getAccountType(),
                    "ownerName", ownerName
                );
            })
            .toList());
    }

    @GetMapping("/api/accounts/search")
    public ResponseEntity<?> searchByNumber(@RequestParam String accountNumber,
                                             @RequestHeader("X-User-Role") String role) {
        if (!List.of("EMPLOYEE", "ADMIN", "AUDITOR").contains(role))
            return ResponseEntity.status(403).body(Map.of("error", "Access denied"));
        return accountRepo.findByAccountNumber(accountNumber)
            .map(ResponseEntity::ok)
            .orElse(ResponseEntity.notFound().build());
    }

    @GetMapping("/api/accounts")
    public ResponseEntity<?> listAllAccounts(@RequestParam(defaultValue = "0") int page,
                                              @RequestParam(defaultValue = "20") int size,
                                              @RequestHeader("X-User-Role") String role) {
        if (!List.of("EMPLOYEE", "ADMIN", "AUDITOR").contains(role))
            return ResponseEntity.status(403).body(Map.of("error", "Access denied"));
        var all = accountRepo.findAll(
            org.springframework.data.domain.PageRequest.of(page, size));
        return ResponseEntity.ok(all);
    }

    // ═══════════════ ATOMIC TRANSFER (internal) ═══════════════

    @PostMapping("/api/accounts/transfer")
    @Transactional
    public ResponseEntity<?> transfer(@RequestBody Map<String, Object> body) {
        UUID fromId = UUID.fromString((String) body.get("fromAccountId"));
        UUID toId = UUID.fromString((String) body.get("toAccountId"));
        BigDecimal amount = new BigDecimal(body.get("amount").toString());
        String correlationId = (String) body.get("correlationId");

        // Debit
        int debitRows = em.createNativeQuery(
            "UPDATE accounts SET balance = balance - ?1, updated_at = NOW() WHERE id = ?2 AND balance >= ?1 AND status = 'ACTIVE'")
            .setParameter(1, amount).setParameter(2, fromId)
            .executeUpdate();
        if (debitRows == 0) return ResponseEntity.badRequest().body(Map.of("success", false, "reason", "INSUFFICIENT_FUNDS"));

        // Credit
        int creditRows = em.createNativeQuery(
            "UPDATE accounts SET balance = balance + ?1, updated_at = NOW() WHERE id = ?2 AND status = 'ACTIVE'")
            .setParameter(1, amount).setParameter(2, toId)
            .executeUpdate();
        if (creditRows == 0) throw new RuntimeException("Destination account invalid");  // triggers rollback

        // Audit
        Account from = accountRepo.findById(fromId).orElseThrow();
        Account to = accountRepo.findById(toId).orElseThrow();
        auditRepo.save(new AuditLog(fromId, "TRANSFER_DEBIT", amount, from.getBalance(), null, correlationId));
        auditRepo.save(new AuditLog(toId, "TRANSFER_CREDIT", amount, to.getBalance(), null, correlationId));

        return ResponseEntity.ok(Map.of("success", true, "transactionId", UUID.randomUUID().toString()));
    }

    // ═══════════════ CLOSE / FREEZE ═══════════════

    @PostMapping("/api/accounts/{id}/close")
    public ResponseEntity<?> closeAccount(@PathVariable UUID id,
                                           @RequestHeader("X-User-Id") UUID userId,
                                           @RequestHeader("X-User-Role") String role) {
        Account account = accountRepo.findById(id).orElseThrow();
        if (!account.getOwnerId().equals(userId) || !List.of("RETAIL", "BUSINESS").contains(role))
            return ResponseEntity.status(403).body(Map.of("error", "Access denied"));
        if (account.getBalance().compareTo(BigDecimal.ZERO) > 0)
            return ResponseEntity.badRequest().body(Map.of("error", "Transfer remaining balance first"));
        if ("RETAIL".equals(role) && accountRepo.countByOwnerIdAndStatus(userId, "ACTIVE") <= 1)
            return ResponseEntity.badRequest().body(Map.of("error", "Cannot close last account"));

        account.setStatus("CLOSED"); account.setUpdatedAt(Instant.now()); accountRepo.save(account);
        auditRepo.save(new AuditLog(id, "CLOSED", null, BigDecimal.ZERO, userId, null));
        return ResponseEntity.ok(Map.of("success", true));
    }

    @PatchMapping("/api/accounts/{id}/status")
    public ResponseEntity<?> changeStatus(@PathVariable UUID id,
                                           @RequestBody Map<String, String> body,
                                           @RequestHeader("X-User-Id") UUID userId,
                                           @RequestHeader("X-User-Role") String role) {
        if (!List.of("EMPLOYEE", "ADMIN").contains(role))
            return ResponseEntity.status(403).body(Map.of("error", "Access denied"));

        String newStatus = body.get("status");
        Account account = accountRepo.findById(id).orElseThrow();
        account.setStatus(newStatus); account.setUpdatedAt(Instant.now()); accountRepo.save(account);
        auditRepo.save(new AuditLog(id, newStatus.equals("FROZEN") ? "FROZEN" : "UNFROZEN",
            null, account.getBalance(), userId, null));
        return ResponseEntity.ok(Map.of("success", true));
    }

    // ═══════════════ EMPLOYEES ═══════════════

    @PostMapping("/api/business/{bizId}/employees")
    public ResponseEntity<?> addEmployees(@PathVariable UUID bizId,
                                           @RequestBody Map<String, Object> body,
                                           @RequestHeader("X-User-Id") UUID userId) {
        if (!bizId.equals(userId)) return ResponseEntity.status(403).body(Map.of("error", "Access denied"));

        @SuppressWarnings("unchecked")
        List<Map<String, String>> employees = (List<Map<String, String>>) body.get("employees");
        int added = 0, skipped = 0;
        for (var emp : employees) {
            UUID empId = UUID.fromString(emp.get("employeeUserId"));
            try {
                BusinessEmployee be = new BusinessEmployee(bizId, empId, emp.get("employeeCode"));
                employeeRepo.save(be);
                added++;
            } catch (Exception e) { skipped++; }
        }
        return ResponseEntity.ok(Map.of("added", added, "skipped", skipped));
    }

    @GetMapping("/api/business/{bizId}/employees")
    public ResponseEntity<?> listEmployees(@PathVariable UUID bizId,
                                            @RequestHeader("X-User-Id") UUID userId,
                                            @RequestHeader("X-User-Role") String role) {
        if (!bizId.equals(userId) && !List.of("EMPLOYEE", "ADMIN").contains(role))
            return ResponseEntity.status(403).body(Map.of("error", "Access denied"));
        return ResponseEntity.ok(employeeRepo.findByBusinessId(bizId));
    }

    @DeleteMapping("/api/business/{bizId}/employees/{empUserId}")
    @Transactional
    public ResponseEntity<?> removeEmployee(@PathVariable UUID bizId,
                                             @PathVariable UUID empUserId,
                                             @RequestHeader("X-User-Id") UUID userId) {
        if (!bizId.equals(userId)) return ResponseEntity.status(403).body(Map.of("error", "Access denied"));

        BusinessEmployee link = employeeRepo.findByBusinessIdAndEmployeeId(bizId, empUserId).orElseThrow();
        link.setStatus("INACTIVE"); employeeRepo.save(link);

        // Convert SALARY → SAVINGS for this employee's salary accounts
        List<Account> salaryAccounts = accountRepo.findByOwnerIdAndStatusIn(empUserId, List.of("ACTIVE"));
        int converted = 0;
        for (Account a : salaryAccounts) {
            if ("SALARY".equals(a.getAccountType()) && bizId.equals(a.getEmployerBusinessId())) {
                a.setAccountType("SAVINGS"); a.setUpdatedAt(Instant.now()); accountRepo.save(a);
                auditRepo.save(new AuditLog(a.getId(), "TYPE_CHANGED", null, a.getBalance(), userId, null));
                converted++;
            }
        }
        return ResponseEntity.ok(Map.of("success", true, "convertedAccounts", converted));
    }
}
