# 📋 User Onboarding & Data Requirements

> Two customer types. Simple rules. Plan for growth.

---

## Customer Types

| | RETAIL | BUSINESS |
|---|:---:|:---:|
| **Who** | Individual person | Registered company |
| **Accounts** | Up to 3 | Unlimited (add anytime) |
| **Employees** | N/A | Can link RETAIL users as employees |
| **Account types allowed** | `SAVINGS`, `SALARY` | `CURRENT` |
| **Min deposit** | ₹1,000 (SAVINGS), ₹0 (SALARY) | ₹10,000 |

---

## Account Types — How They Differ

| | SAVINGS | SALARY | CURRENT |
|---|:---:|:---:|:---:|
| **Who can open** | RETAIL | RETAIL | BUSINESS |
| **Min balance** | ₹1,000 | ₹0 | ₹10,000 |
| **Employer link** | None | Required (must be in employer's employee list) | None |
| **Interest (Phase 2)** | Yes (monthly) | No | No |
| **Converts to** | — | SAVINGS (if employer link breaks) | — |
| **Use case** | Personal savings | Salary credited by employer | Business operations |

---

## RETAIL Onboarding — Priya

Register → profile → open first account → add more accounts anytime (up to 3).

### Step 1: Register
```
POST /api/auth/register
{ "email": "priya@email.com", "password": "SecureP@ss123" }
→ { "userId": "usr_abc", "token": "eyJ..." }
```

| Field | Stored In | Notes |
|-------|-----------|-------|
| `email` | Auth DB | Login + notifications |
| `password` | Auth DB (bcrypt) | — |
| `role` | Auth DB | Auto-set to `RETAIL` |

### Step 2: Complete Profile
```
POST /api/accounts/profile
{ "fullName": "Priya Sharma", "phone": "+919876...", 
  "dateOfBirth": "1997-03-15", "panNumber": "ABCDP1234E" }
```

| Field | Required | Stored In |
|-------|:---:|------|
| `fullName` | ✅ | Account DB → `retail_profiles` |
| `phone` | ✅ | Account DB |
| `dateOfBirth` | ✅ | Account DB (must be 18+) |
| `panNumber` | ✅ | Account DB |
| `aadhaarLast4` | ⬜ | Account DB |
| `address` | ⬜ | Account DB |

### Step 3: Open First Account (at onboarding)
```
POST /api/accounts
{ "accountType": "SAVINGS", "label": "Primary Savings", "initialDeposit": 5000 }
→ { "accountId": "acc_001", "accountNumber": "100200030004", "balance": 5000 }
```

### Step 4: Add More Accounts (anytime, up to 3 total)

**Salary account** — Priya gets a job at ABC Exports:
```
POST /api/accounts
{
  "accountType": "SALARY",
  "label": "ABC Exports Salary",
  "employerBusinessId": "biz_xyz",
  "initialDeposit": 0
}
→ System checks: is Priya in biz_xyz's employee list? ✅
→ { "accountId": "acc_002", "accountNumber": "100200030005", "balance": 0 }
```

**Another savings account** — separate emergency fund:
```
POST /api/accounts
{ "accountType": "SAVINGS", "label": "Emergency Fund", "initialDeposit": 30000 }
→ { "accountId": "acc_003", "accountNumber": "100200030006", "balance": 30000 }
```

### Priya's Data After Full Setup
```
Auth DB:       users(id=usr_abc, email, role=RETAIL)
Account DB:    retail_profiles(user_id=usr_abc, fullName, pan, ...)
               accounts(owner_id=usr_abc, type=SAVINGS, label="Primary Savings", balance=5000)
               accounts(owner_id=usr_abc, type=SALARY, label="ABC Exports Salary", balance=0,
                         employer_business_id=biz_xyz)
               accounts(owner_id=usr_abc, type=SAVINGS, label="Emergency Fund", balance=30000)
```

---

## BUSINESS Onboarding — ABC Exports

Register company → complete business profile → open first account → add more accounts anytime → add employees anytime.

### Step 1: Register
```
POST /api/auth/register
{ "email": "cfo@abcexports.com", "password": "BizP@ss123", "role": "BUSINESS" }
→ { "userId": "biz_xyz", "token": "eyJ..." }
```

### Step 2: Complete Business Profile
```
POST /api/accounts/business-profile
{
  "companyName": "ABC Exports Ltd.",
  "contactName": "Vikram Mehta",
  "contactPhone": "+919811122233",
  "gstNumber": "27AABCT1234E1Z5",
  "panNumber": "AABCT1234E",
  "businessType": "PVT_LTD",
  "registeredAddress": "15, Industrial Area, Mumbai - 400093"
}
```

| Field | Required | Stored In |
|-------|:---:|------|
| `companyName` | ✅ | Account DB → `business_profiles` |
| `contactName` | ✅ | Account DB |
| `contactPhone` | ✅ | Account DB |
| `gstNumber` | ✅ | Account DB |
| `panNumber` | ✅ | Account DB |
| `businessType` | ✅ | Account DB (PROPRIETORSHIP/PARTNERSHIP/PVT_LTD/LLP) |
| `registeredAddress` | ✅ | Account DB |
| `annualTurnover` | ⬜ | Account DB (Phase 2 credit assessment) |

### Step 3: Open First Current Account (at onboarding)
```
POST /api/accounts
{ "accountType": "CURRENT", "label": "Operations", "initialDeposit": 100000 }
→ { "accountId": "acc_201", "accountNumber": "500200030001", "balance": 100000 }
```

### Step 4: Add More Accounts (anytime after onboarding)
```
POST /api/accounts
{ "accountType": "CURRENT", "label": "Payroll", "initialDeposit": 500000 }
→ { "accountId": "acc_202", "accountNumber": "500200030002", "balance": 500000 }

POST /api/accounts
{ "accountType": "CURRENT", "label": "Export Revenue", "initialDeposit": 2500000 }
→ { "accountId": "acc_203", "accountNumber": "500200030003", "balance": 2500000 }
```
No limit on number of accounts. Each gets a unique `label`.

### Step 5: Add Employees (during onboarding OR after)

```
POST /api/business/{bizId}/employees
[
  { "employeeUserId": "usr_priya", "employeeCode": "EMP-042" },
  { "employeeUserId": "usr_rahul", "employeeCode": "EMP-043" }
]
```

| Field | Required | Notes |
|-------|:---:|-------|
| `employeeUserId` | ✅ | Must be an existing RETAIL user registered with us |
| `employeeCode` | ⬜ | Business's internal employee ID |
| `status` | Auto | `ACTIVE` (auto-accepted in MVP, approval flow in Phase 2) |

**Timing:** Can happen during onboarding (Step 3.5) or any time after (Step 5, 6, ...). Business sends the list whenever they're ready.

### ABC Exports' Data After Full Setup
```
Auth DB:       users(id=biz_xyz, email, role=BUSINESS)

Account DB:    business_profiles(user_id=biz_xyz, companyName, gst, ...)
               
               accounts(owner_id=biz_xyz, label="Operations", type=CURRENT)
               accounts(owner_id=biz_xyz, label="Payroll", type=CURRENT)
               accounts(owner_id=biz_xyz, label="Export Revenue", type=CURRENT)
               
               business_employees(business_id=biz_xyz, employee_id=usr_priya, code=EMP-042)
               business_employees(business_id=biz_xyz, employee_id=usr_rahul, code=EMP-043)
```

---

## Strong Ownership Model

In microservices, DBs are separate — no foreign keys across services. We enforce ownership at the **application level**.

### How Ownership Is Enforced

```
┌─────────────┐         ┌──────────────────┐
│  Auth DB    │         │   Account DB     │
│             │         │                  │
│  users      │         │  accounts        │
│  ┌───────┐  │         │  ┌──────────────┐│
│  │biz_xyz│──┼────?────┼─►│owner_id=     ││
│  │BUSINESS│  │  (no    │  │  biz_xyz     ││  ← Logical FK
│  └───────┘  │   DB FK) │  │label=Payroll ││     (application
│             │         │  └──────────────┘│      enforced)
└─────────────┘         └──────────────────┘
```

### Rules Enforced in Code

```java
// Creating an account — verify business exists
public Account createAccount(String ownerId, CreateAccountRequest req) {
    // 1. Verify owner exists in Auth Service
    User owner = authClient.getUser(ownerId);  // REST call
    if (owner == null || owner.role != "BUSINESS") throw 400;
    
    // 2. Verify label is unique per business
    if (accountRepo.existsByOwnerIdAndLabel(ownerId, req.label)) throw 409;
    
    // 3. Create account with strong ownership
    Account account = new Account(ownerId, req.label, ...);
    return accountRepo.save(account);
}

// Fetching accounts — only return accounts owned by this business
public List<Account> getAccounts(String ownerId, String requestedBy) {
    if (!ownerId.equals(requestedBy)) throw 403;  // JWT sub must match
    return accountRepo.findByOwnerId(ownerId);
}

// Transfer — from_account must be owned by JWT subject
public void transfer(String fromAccountId, String jwtSubject) {
    Account from = accountRepo.findById(fromAccountId);
    if (!from.ownerId.equals(jwtSubject)) throw 403;
    // ... proceed with transfer
}
```

### What "Strong Relational Link" Means

| Guarantee | How |
|-----------|-----|
| Account X belongs to Business Y | `accounts.owner_id` = JWT `sub`. Enforced on every read/write. |
| No orphaned accounts | Deleting a business (ADMIN action) cascades to accounts. Application-level cascade. |
| Employee E works for Business B | `business_employees.business_id` + `employee_id`. Both must exist. |
| Business cannot see another business's accounts | Query always filters by `owner_id` from JWT. |
| RETAIL user cannot pretend to be a BUSINESS account owner | `owner.role` checked at account creation. |

---

## Database Tables

### Auth DB (`auth_db`)
```sql
users (
    id            UUID PRIMARY KEY,
    email         VARCHAR(255) UNIQUE NOT NULL,
    password_hash VARCHAR(255) NOT NULL,
    role          VARCHAR(20) NOT NULL,   -- RETAIL | BUSINESS | EMPLOYEE | ADMIN | AUDITOR
    created_at    TIMESTAMP DEFAULT NOW()
);
```

### Account DB (`account_db`)
```sql
retail_profiles (
    id            UUID PRIMARY KEY,
    user_id       UUID UNIQUE NOT NULL,   -- logical FK → auth.users
    full_name     VARCHAR(255) NOT NULL,
    phone         VARCHAR(20) NOT NULL,
    date_of_birth DATE NOT NULL,
    pan_number    VARCHAR(10) NOT NULL,
    aadhaar_last4 VARCHAR(4),
    address       TEXT,
    created_at    TIMESTAMP DEFAULT NOW()
);

business_profiles (
    id              UUID PRIMARY KEY,
    user_id         UUID UNIQUE NOT NULL,
    company_name    VARCHAR(255) NOT NULL,
    contact_name    VARCHAR(255) NOT NULL,
    contact_phone   VARCHAR(20) NOT NULL,
    gst_number      VARCHAR(15) NOT NULL,
    pan_number      VARCHAR(10) NOT NULL,
    business_type   VARCHAR(30) NOT NULL,
    registered_addr TEXT NOT NULL,
    annual_turnover DECIMAL(15,2),
    created_at      TIMESTAMP DEFAULT NOW()
);

accounts (
    id                  UUID PRIMARY KEY,
    owner_id            UUID NOT NULL,             -- logical FK → auth.users
    account_number      VARCHAR(12) UNIQUE NOT NULL,
    account_type        VARCHAR(20) NOT NULL,       -- SAVINGS | SALARY | CURRENT
    label               VARCHAR(100),               -- "Primary Savings", "Payroll"
    balance             DECIMAL(15,2) NOT NULL DEFAULT 0 CHECK (balance >= 0),
    employer_business_id UUID,                      -- only for SALARY accounts
    status              VARCHAR(20) DEFAULT 'ACTIVE',
    created_at          TIMESTAMP DEFAULT NOW(),
    
    UNIQUE(owner_id, label)
);

business_employees (
    id              UUID PRIMARY KEY,
    business_id     UUID NOT NULL,             -- logical FK → auth.users (BUSINESS)
    employee_id     UUID NOT NULL,             -- logical FK → auth.users (RETAIL)
    employee_code   VARCHAR(50),               -- company's internal code
    status          VARCHAR(20) DEFAULT 'ACTIVE',
    created_at      TIMESTAMP DEFAULT NOW(),
    
    UNIQUE(business_id, employee_id)
);
```

---

## Account Limits

| Rule | RETAIL | BUSINESS |
|------|:---:|:---:|
| Max accounts | **3** | Unlimited |
| Account types | `SAVINGS`, `SALARY` | `CURRENT` |
| Max SALARY accounts | 1 (one employer at a time) | N/A |
| Can close last account? | ❌ (must have at least 1) | ✅ (can have 0) |
| Min balance (SAVINGS/CURRENT) | ₹1,000 | ₹10,000 |
| Min balance (SALARY) | ₹0 | N/A |
| Label required? | Yes (unique per user) | ✅ (unique per business) |

---

## Account Lifecycle

### Opening
- RETAIL: max 3 accounts. SALARY requires valid `employerBusinessId` and user must be in that business's employee list.
- BUSINESS: unlimited accounts. Each needs a unique `label`.

### Closing
```
POST /api/accounts/{id}/close
```
- Balance must be ₹0 (transfer remaining funds out first)
- Status changes to `CLOSED` — still visible in history but no new transactions
- RETAIL: cannot close if it's the only account
- SALARY account: closing it does NOT remove the employer link

### Salary Account Lifecycle
```
SALARY account opened (linked to biz_xyz)
  → If employee link is deactivated (Priya leaves ABC Exports):
      account_type changes from SALARY → SAVINGS
      min_balance rule kicks in (₹1,000)
      label stays as-is
  → Priya can also manually convert SALARY → SAVINGS anytime
```

### Password Reset
```
POST /api/auth/forgot-password
{ "email": "priya@email.com" }
→ Always returns 200 (don't reveal if email exists)
→ Generates reset token (15-min expiry)
→ Sends reset link via Notification Service (mock: log to console)

POST /api/auth/reset-password
{ "token": "eyJ...", "newPassword": "NewP@ss456" }
→ Validates token → updates password → invalidates all existing JWTs
```

---

## Validation

| Field | Rule |
|-------|------|
| `email` | Valid format, unique |
| `password` | Min 8 chars, 1 upper, 1 digit, 1 special |
| `phone` | 10 digits, starts with 6-9 |
| `dateOfBirth` | 18+ years |
| `panNumber` | 10 chars: ABCDP1234E |
| `gstNumber` | 15 chars |
| `accountType` | RETAIL → `SAVINGS` or `SALARY`. BUSINESS → `CURRENT`. |
| `employerBusinessId` | Required for SALARY. User must be in that business's employee list. |
| `label` | Required. Unique per owner. Max 50 chars. |

---

## What We're NOT Doing (MVP)

| Skipped | When |
|---------|------|
| Multiple users per business (maker/checker) | Phase 2 |
| Employee approval flow (accept/reject link) | Phase 2 (auto-accept for now) |
| Document uploads (PAN image, GST cert) | Phase 2 |
| Video KYC | Phase 3 |
| Nominee details | Phase 2 |
| Credit score / CIBIL | Phase 2 |
| Fixed deposits, overdrafts | Phase 2 |
