# Account Service — Inbound Requests & Actions

## Retail Flows

### Complete Retail Profile
```
POST /api/accounts/profile { fullName, phone, dateOfBirth, panNumber, aadhaarLast4?, address? }
  → Validate: phone 10-digit, dob >= 18 years, panNumber format ABCDP1234E
  → INSERT retail_profiles (user_id=JWT.sub, ...) ON CONFLICT (user_id) DO NOTHING
  → Return profile
```

### Open Account (RETAIL: SAVINGS or SALARY)
```
POST /api/accounts { accountType, label, initialDeposit, employerBusinessId? }
  → Verify retail_profiles exists for JWT.sub → 400 if not
  → COUNT accounts WHERE owner_id=JWT.sub AND status='ACTIVE' → >= 3 → 400 "Max 3 accounts"
  → If accountType=SALARY:
      → employerBusinessId required
      → Verify business exists: call Auth GET /api/auth/users/{employerBusinessId} → role must be BUSINESS
      → Verify employee link: SELECT business_employees WHERE business_id=? AND employee_id=? AND status='ACTIVE'
      → Not found → 400 "Not an employee of this business"
      → COUNT accounts WHERE owner_id=JWT.sub AND account_type='SALARY' → >0 → 400 "Already have salary account"
      → minDeposit = 0
  → If accountType=SAVINGS:
      → minDeposit = 1000
      → initialDeposit >= 1000 → else 400
  → Check label UNIQUE per owner
  → Generate account_number (12-digit: "1002" + 8-digit sequence)
  → INSERT accounts (owner_id, account_number, account_type, label, balance=initialDeposit, employer_business_id?, status=ACTIVE)
  → INSERT audit_log (action=CREATED, balance_after=initialDeposit)
  → Return { accountId, accountNumber, accountType, label, balance }
```

### Open Account (BUSINESS: CURRENT)
```
POST /api/accounts { accountType: CURRENT, label, initialDeposit }
  → Verify business_profiles exists for JWT.sub
  → No max account limit
  → initialDeposit >= 10000 → else 400
  → Check label UNIQUE per owner
  → Same INSERT flow as above, no employer_business_id
  → Return account
```

### Atomic Transfer (called by Payment Service, internal)
```
POST /api/accounts/transfer { fromAccountId, toAccountId, amount, correlationId }
  → BEGIN TXN
  → UPDATE accounts SET balance=balance-?, updated_at=NOW()
    WHERE id=? AND balance>=? AND status='ACTIVE'
  → rows_affected=0 → ROLLBACK, return { success: false, reason: "INSUFFICIENT_FUNDS" }
  → UPDATE accounts SET balance=balance+?, updated_at=NOW()
    WHERE id=? AND status='ACTIVE'
  → rows_affected=0 → ROLLBACK, return { success: false, reason: "DESTINATION_INVALID" }
  → INSERT audit_log (account_id=from, action=TRANSFER_DEBIT, amount, balance_after, reference="payment:{paymentId}", correlation_id)
  → INSERT audit_log (account_id=to, action=TRANSFER_CREDIT, amount, balance_after, reference="payment:{paymentId}", correlation_id)
  → COMMIT
  → Return { success: true, transactionId: UUID }
```

### Get Account
```
GET /api/accounts/{id}
  → SELECT account WHERE id=?
  → If RETAIL/BUSINESS: verify owner_id = JWT.sub → else 403
  → If EMPLOYEE/ADMIN/AUDITOR: no restriction
  → Return { id, accountNumber, accountType, label, balance, status, employerBusinessId?, createdAt }
```

### List My Accounts
```
GET /api/accounts/user/{userId}
  → Verify userId = JWT.sub (RETAIL/BUSINESS) OR role=EMPLOYEE/ADMIN/AUDITOR
  → SELECT accounts WHERE owner_id=? AND status IN ('ACTIVE','FROZEN')
  → Return list
```

### Search by Account Number (EMPLOYEE/ADMIN/AUDITOR)
```
GET /api/accounts/search?accountNumber=500200030001
  → Verify role IN (EMPLOYEE, ADMIN, AUDITOR) → else 403
  → SELECT account WHERE account_number=?
  → If found: also fetch owner profile (retail_profiles or business_profiles by owner_id)
  → Return { account, owner }
```

### Close Account
```
POST /api/accounts/{id}/close
  → SELECT account WHERE id=?
  → Verify owner_id = JWT.sub (RETAIL/BUSINESS only)
  → Check balance = 0 → else 400 "Transfer remaining balance first"
  → If RETAIL: COUNT accounts WHERE owner_id=? AND status='ACTIVE' → =1 → 400 "Cannot close last account"
  → UPDATE accounts SET status='CLOSED', updated_at=NOW()
  → INSERT audit_log (action=CLOSED, performed_by=JWT.sub)
  → Return { success }
```

### Freeze Account (EMPLOYEE/ADMIN)
```
PATCH /api/accounts/{id}/status { status: "FROZEN" }
  → Verify role IN (EMPLOYEE, ADMIN)
  → UPDATE accounts SET status='FROZEN', updated_at=NOW() WHERE id=?
  → INSERT audit_log (action=FROZEN, performed_by=JWT.sub)
  → Return { success }
```

### Unfreeze Account (EMPLOYEE/ADMIN)
```
PATCH /api/accounts/{id}/status { status: "ACTIVE" }
  → Same as freeze but status='ACTIVE', audit_log action=UNFROZEN
```

## Business Flows

### Complete Business Profile
```
POST /api/accounts/business-profile { companyName, contactName, contactPhone, gstNumber, panNumber, businessType, registeredAddress, annualTurnover? }
  → Verify role = BUSINESS
  → Validate: gstNumber 15 chars, panNumber 10 chars, businessType IN ('PROPRIETORSHIP','PARTNERSHIP','PVT_LTD','LLP')
  → INSERT business_profiles (user_id=JWT.sub, ...)
  → Return profile
```

### Add Employees
```
POST /api/business/{bizId}/employees { employees: [{ employeeUserId, employeeCode }] }
  → Verify bizId = JWT.sub AND role=BUSINESS
  → For each employee:
      → Call Auth GET /api/auth/users/{employeeUserId} → verify exists and role=RETAIL
      → Skip if not RETAIL
      → INSERT business_employees (business_id=bizId, employee_id, employee_code) ON CONFLICT DO NOTHING
  → Return { added: N, skipped: M }
```

### Remove Employee
```
DELETE /api/business/{bizId}/employees/{empUserId}
  → UPDATE business_employees SET status='INACTIVE' WHERE business_id=? AND employee_id=?
  → Find SALARY accounts: SELECT id FROM accounts WHERE employer_business_id=? AND owner_id=? AND account_type='SALARY'
  → UPDATE accounts SET account_type='SAVINGS', updated_at=NOW() WHERE id IN (...)
  → INSERT audit_log (action=TYPE_CHANGED) for each converted account
  → Return { success, convertedAccounts: N }
```

### List Employees
```
GET /api/business/{bizId}/employees
  → Verify bizId = JWT.sub (BUSINESS) OR role IN (EMPLOYEE, ADMIN)
  → SELECT * FROM business_employees WHERE business_id=?
  → Return list: [{ employeeUserId, employeeCode, status }]
```

### Get Business Profile
```
GET /api/accounts/business-profile
  → Return business_profiles WHERE user_id=JWT.sub
```

## Exposed Endpoints Summary
```
POST   /api/accounts                     (create, RETAIL/BUSINESS)
POST   /api/accounts/profile             (retail profile)
POST   /api/accounts/business-profile    (business profile)
GET    /api/accounts/business-profile    (view business profile)
PUT    /api/accounts/business-profile    (edit business profile)
GET    /api/accounts/{id}                (view one)
GET    /api/accounts/user/{userId}       (list by owner)
GET    /api/accounts/search              (by account number, EMPLOYEE+)
POST   /api/accounts/{id}/close          (close, RETAIL/BUSINESS)
PATCH  /api/accounts/{id}/status         (freeze/unfreeze, EMPLOYEE+)
POST   /api/accounts/transfer            (internal, called by Payment)
POST   /api/business/{bizId}/employees   (add employees)
DELETE /api/business/{bizId}/employees/{empId}  (remove employee)
GET    /api/business/{bizId}/employees   (list employees)
```
