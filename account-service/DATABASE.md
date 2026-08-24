# 💰 Account Service — Database Design

> **Database:** `account_db`  
> **Purpose:** Store user profiles (retail + business), accounts, employee links, and audit logs. This is the core banking domain.

---

## ER Diagram (Logical)

```
┌──────────────────────┐       ┌──────────────────────┐
│   retail_profiles    │       │  business_profiles   │
├──────────────────────┤       ├──────────────────────┤
│ id          PK UUID  │       │ id          PK UUID  │
│ user_id     UNIQUE   │       │ user_id     UNIQUE   │
│ full_name            │       │ company_name         │
│ phone                │       │ contact_name         │
│ date_of_birth        │       │ contact_phone        │
│ pan_number           │       │ gst_number           │
│ aadhaar_last4        │       │ pan_number           │
│ address              │       │ business_type        │
│ created_at           │       │ registered_addr      │
└──────────┬───────────┘       │ annual_turnover      │
           │                   │ created_at           │
           │ 1                 └──────────┬───────────┘
           │                              │ 1
           │                              │
           ▼                              ▼
┌──────────────────────────────────────────────────────┐
│                      accounts                        │
├──────────────────────────────────────────────────────┤
│ id              PK UUID                              │
│ owner_id        UUID NOT NULL   ← owner (RETAIL/BUSINESS) │
│ account_number  VARCHAR(12) UNIQUE                    │
│ account_type    VARCHAR(20)     ← SAVINGS|SALARY|CURRENT │
│ label           VARCHAR(100)                          │
│ balance         DECIMAL(15,2)   CHECK >= 0             │
│ employer_biz_id UUID            ← only for SALARY      │
│ status          VARCHAR(20)     ← ACTIVE|FROZEN|CLOSED │
│ created_at      TIMESTAMP                             │
│ updated_at      TIMESTAMP                             │
└──────────────────────┬───────────────────────────────┘
                       │
                       │ 1
                       │
                       ▼ N
┌──────────────────────────────────────┐
│          audit_log                   │
├──────────────────────────────────────┤
│ id              PK BIGSERIAL         │
│ account_id      UUID NOT NULL        │
│ action          VARCHAR(50)          │  ← TRANSFER_DEBIT|TRANSFER_CREDIT|CREATED|CLOSED|FROZEN|UNFROZEN
│ amount          DECIMAL(15,2)        │
│ balance_after   DECIMAL(15,2)        │
│ reference       VARCHAR(255)         │  ← payment ID or operation ID
│ performed_by    UUID                 │  ← JWT sub of who did it
│ correlation_id  VARCHAR(36)          │
│ created_at      TIMESTAMP            │
└──────────────────────────────────────┘
                       │
                       │                    ┌──────────────────────────┐
                       │                    │   business_employees     │
                       │                    ├──────────────────────────┤
                       │                    │ id           PK UUID     │
                       │                    │ business_id  UUID NOT NULL│
                       │                    │ employee_id  UUID NOT NULL│
                       │                    │ employee_code VARCHAR(50) │
                       │                    │ status       VARCHAR(20) │
                       │                    │ created_at   TIMESTAMP   │
                       │                    └──────────────────────────┘
```

---

## Tables

### `retail_profiles`

One row per RETAIL user. Created during onboarding step 2.

| Column | Type | Constraints | Notes |
|--------|------|-------------|-------|
| `id` | UUID | PK | |
| `user_id` | UUID | UNIQUE, NOT NULL | Logical FK → auth_db.users |
| `full_name` | VARCHAR(255) | NOT NULL | |
| `phone` | VARCHAR(20) | NOT NULL | 10-digit Indian mobile |
| `date_of_birth` | DATE | NOT NULL | Must be 18+ |
| `pan_number` | VARCHAR(10) | NOT NULL | ABCDP1234E format. Locked after set. |
| `aadhaar_last4` | VARCHAR(4) | | Masked for MVP |
| `address` | TEXT | | |
| `created_at` | TIMESTAMP | DEFAULT NOW() | |

```sql
CREATE TABLE retail_profiles (
    id              UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id         UUID UNIQUE NOT NULL,
    full_name       VARCHAR(255) NOT NULL,
    phone           VARCHAR(20) NOT NULL,
    date_of_birth   DATE NOT NULL,
    pan_number      VARCHAR(10) NOT NULL,
    aadhaar_last4   VARCHAR(4),
    address         TEXT,
    created_at      TIMESTAMP DEFAULT NOW()
);

CREATE INDEX idx_retail_user ON retail_profiles(user_id);
```

### `business_profiles`

One row per BUSINESS user. Created during onboarding step 2.

| Column | Type | Constraints | Notes |
|--------|------|-------------|-------|
| `id` | UUID | PK | |
| `user_id` | UUID | UNIQUE, NOT NULL | Logical FK → auth_db.users |
| `company_name` | VARCHAR(255) | NOT NULL | |
| `contact_name` | VARCHAR(255) | NOT NULL | Primary contact person |
| `contact_phone` | VARCHAR(20) | NOT NULL | |
| `gst_number` | VARCHAR(15) | NOT NULL | 27AABCT1234E1Z5 format. Locked after set. |
| `pan_number` | VARCHAR(10) | NOT NULL | Company PAN. Locked after set. |
| `business_type` | VARCHAR(30) | NOT NULL | PROPRIETORSHIP, PARTNERSHIP, PVT_LTD, LLP |
| `registered_addr` | TEXT | NOT NULL | |
| `annual_turnover` | DECIMAL(15,2) | | Phase 2: credit assessment |
| `created_at` | TIMESTAMP | DEFAULT NOW() | |

```sql
CREATE TABLE business_profiles (
    id              UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id         UUID UNIQUE NOT NULL,
    company_name    VARCHAR(255) NOT NULL,
    contact_name    VARCHAR(255) NOT NULL,
    contact_phone   VARCHAR(20) NOT NULL,
    gst_number      VARCHAR(15) NOT NULL,
    pan_number      VARCHAR(10) NOT NULL,
    business_type   VARCHAR(30) NOT NULL CHECK (business_type IN ('PROPRIETORSHIP','PARTNERSHIP','PVT_LTD','LLP')),
    registered_addr TEXT NOT NULL,
    annual_turnover DECIMAL(15,2),
    created_at      TIMESTAMP DEFAULT NOW()
);

CREATE INDEX idx_business_user ON business_profiles(user_id);
```

### `accounts`

The core table. Every account — retail savings, salary, or business current — is one row.

| Column | Type | Constraints | Notes |
|--------|------|-------------|-------|
| `id` | UUID | PK | Internal ID |
| `owner_id` | UUID | NOT NULL | Logical FK → auth_db.users. Links to RETAIL or BUSINESS. |
| `account_number` | VARCHAR(12) | UNIQUE, NOT NULL | Public-facing 12-digit number |
| `account_type` | VARCHAR(20) | NOT NULL, CHECK | `SAVINGS`, `SALARY`, `CURRENT` |
| `label` | VARCHAR(100) | NOT NULL | User-given name. Unique per owner. |
| `balance` | DECIMAL(15,2) | NOT NULL, DEFAULT 0, CHECK >= 0 | Cannot go negative |
| `employer_business_id` | UUID | | Only for SALARY. Logical FK → auth_db.users (BUSINESS). |
| `status` | VARCHAR(20) | DEFAULT 'ACTIVE', CHECK | `ACTIVE`, `FROZEN`, `CLOSED` |
| `created_at` | TIMESTAMP | DEFAULT NOW() | |
| `updated_at` | TIMESTAMP | DEFAULT NOW() | Updated on balance change |

```sql
CREATE TABLE accounts (
    id                  UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    owner_id            UUID NOT NULL,
    account_number      VARCHAR(12) UNIQUE NOT NULL,
    account_type        VARCHAR(20) NOT NULL CHECK (account_type IN ('SAVINGS','SALARY','CURRENT')),
    label               VARCHAR(100) NOT NULL,
    balance             DECIMAL(15,2) NOT NULL DEFAULT 0 CHECK (balance >= 0),
    employer_business_id UUID,
    status              VARCHAR(20) DEFAULT 'ACTIVE' CHECK (status IN ('ACTIVE','FROZEN','CLOSED')),
    created_at          TIMESTAMP DEFAULT NOW(),
    updated_at          TIMESTAMP DEFAULT NOW(),
    
    UNIQUE(owner_id, label)
);

CREATE INDEX idx_accounts_owner ON accounts(owner_id);
CREATE INDEX idx_accounts_number ON accounts(account_number);
CREATE INDEX idx_accounts_type ON accounts(account_type);
CREATE INDEX idx_accounts_status ON accounts(status);
```

### `audit_log`

Immutable record of every account-level operation. Used for internal audit & debugging.

| Column | Type | Constraints | Notes |
|--------|------|-------------|-------|
| `id` | BIGSERIAL | PK | Auto-increment |
| `account_id` | UUID | NOT NULL | Which account |
| `action` | VARCHAR(50) | NOT NULL | `TRANSFER_DEBIT`, `TRANSFER_CREDIT`, `CREATED`, `CLOSED`, `FROZEN`, `UNFROZEN` |
| `amount` | DECIMAL(15,2) | | Amount involved |
| `balance_after` | DECIMAL(15,2) | | Balance AFTER this operation |
| `reference` | VARCHAR(255) | | Payment ID or operation ID for traceability |
| `performed_by` | UUID | | JWT sub of the user who did this |
| `correlation_id` | VARCHAR(36) | | Links to the full request trace |
| `created_at` | TIMESTAMP | DEFAULT NOW() | |

```sql
-- Partitioned by month on created_at.
CREATE TABLE audit_log (
    id              BIGSERIAL,
    account_id      UUID NOT NULL,
    action          VARCHAR(50) NOT NULL,
    amount          DECIMAL(15,2),
    balance_after   DECIMAL(15,2),
    reference       VARCHAR(255),
    performed_by    UUID,
    correlation_id  VARCHAR(36),
    created_at      TIMESTAMP DEFAULT NOW(),
    
    PRIMARY KEY (id, created_at)
) PARTITION BY RANGE (created_at);

-- Monthly partitions:
CREATE TABLE audit_log_2026_07 PARTITION OF audit_log
    FOR VALUES FROM ('2026-07-01') TO ('2026-08-01');

CREATE INDEX idx_audit_account ON audit_log(account_id, created_at);
CREATE INDEX idx_audit_action ON audit_log(action, created_at);
-- NO UPDATE, NO DELETE — append only
```

### `business_employees`

Links RETAIL users as employees of a BUSINESS. Used for salary accounts, bulk payments, and employer verification.

| Column | Type | Constraints | Notes |
|--------|------|-------------|-------|
| `id` | UUID | PK | |
| `business_id` | UUID | NOT NULL | Logical FK → auth_db.users (BUSINESS) |
| `employee_id` | UUID | NOT NULL | Logical FK → auth_db.users (RETAIL) |
| `employee_code` | VARCHAR(50) | | Company's internal employee ID (e.g., "EMP-042") |
| `status` | VARCHAR(20) | DEFAULT 'ACTIVE' | `ACTIVE`, `INACTIVE` |
| `created_at` | TIMESTAMP | DEFAULT NOW() | |

```sql
CREATE TABLE business_employees (
    id              UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    business_id     UUID NOT NULL,
    employee_id     UUID NOT NULL,
    employee_code   VARCHAR(50),
    status          VARCHAR(20) DEFAULT 'ACTIVE' CHECK (status IN ('ACTIVE','INACTIVE')),
    created_at      TIMESTAMP DEFAULT NOW(),
    
    UNIQUE(business_id, employee_id)
);

CREATE INDEX idx_be_business ON business_employees(business_id);
CREATE INDEX idx_be_employee ON business_employees(employee_id);
```

---

## The Atomic Transfer — How These Tables Work Together

```sql
-- Called by Payment Service: POST /api/accounts/transfer
-- All in ONE transaction:

BEGIN;

    -- Step 1: Debit source (fails if insufficient balance)
    UPDATE accounts 
    SET balance = balance - :amount, updated_at = NOW()
    WHERE id = :fromAccountId AND balance >= :amount AND status = 'ACTIVE';
    
    -- Check rows_affected. If 0 → insufficient balance or frozen/closed → ROLLBACK.

    -- Step 2: Credit destination
    UPDATE accounts 
    SET balance = balance + :amount, updated_at = NOW()
    WHERE id = :toAccountId AND status = 'ACTIVE';

    -- Step 3: Record in audit log
    INSERT INTO audit_log (account_id, action, amount, balance_after, reference, performed_by, correlation_id)
    SELECT :fromAccountId, 'TRANSFER_DEBIT', :amount, balance, :reference, :userId, :correlationId
    FROM accounts WHERE id = :fromAccountId;

    INSERT INTO audit_log (account_id, action, amount, balance_after, reference, performed_by, correlation_id)
    SELECT :toAccountId, 'TRANSFER_CREDIT', :amount, balance, :reference, :userId, :correlationId
    FROM accounts WHERE id = :toAccountId;

COMMIT;
```

---

## Account Number Generation

```
Format: B B B B A A A A A A A A  (12 digits)
         │     │
         │     └── 8-digit sequential (per branch)
         └── 4-digit branch code

For MVP (single branch: 1002):
  Account 1 → 100200000001
  Account 2 → 100200000002
  ...
  
Generated by: SELECT '1002' || LPAD(nextval('account_number_seq')::text, 8, '0')
```

---

## Cross-Service Relationships

```
Account DB (this service)          Auth DB
accounts(owner_id=biz_xyz)  ──►   users(id=biz_xyz, role=BUSINESS)
accounts(employer_biz_id)   ──►   users(id=biz_xyz, role=BUSINESS)
retail_profiles(user_id)    ──►   users(id=usr_abc, role=RETAIL)
business_profiles(user_id)  ──►   users(id=biz_xyz, role=BUSINESS)
business_employees(...)     ──►   users(both business + employee)

Account DB                         Payment DB
accounts(id)                 ◄──   payments(from_account, to_account)
```
