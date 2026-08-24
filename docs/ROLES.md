# 👥 Roles & Permissions — Banking Microservices

> Who can do what. Keep it simple.

---

## Roles

| Role | Who | Example | Can register self? |
|------|-----|---------|:---:|
| **RETAIL** | Individual banking customer | Priya (salaried worker) | ✅ |
| **BUSINESS** | Corporate / business account | ABC Exports Ltd. | ✅ |
| **EMPLOYEE** | Bank staff — operations, lending | Rajesh (ops), Loan officer | ❌ (provisioned) |
| **ADMIN** | System administrator | DevOps, security team | ❌ (provisioned) |
| **AUDITOR** | External regulator | RBI auditor | ❌ (provisioned) |

---

## Permissions Matrix

| Action | RETAIL | BUSINESS | EMPLOYEE | ADMIN | AUDITOR |
|--------|:---:|:---:|:---:|:---:|:---:|
| **Auth** | | | | | |
| Register / Login | ✅ | ✅ | ✅ | ✅ | ❌ |
| View own profile | ✅ | ✅ | ✅ | ✅ | ✅ |
| **Accounts** | | | | | |
| Create savings account | ✅ | ❌ | ✅ | ✅ | ❌ |
| Create current account | ❌ | ✅ | ✅ | ✅ | ❌ |
| View own account balance | ✅ | ✅ | ✅ | ✅ | ✅ |
| View any account balance | ❌ | ❌ | ✅ | ✅ | ✅ |
| Freeze / Unfreeze account | ❌ | ❌ | ✅ | ✅ | ❌ |
| Transfer money (own account) | ✅ | ✅ | ❌ | ❌ | ❌ |
| Bulk / batch payments | ❌ | ✅ | ❌ | ❌ | ❌ |
| **Transactions** | | | | | |
| View own transaction history | ✅ | ✅ | ✅ | ✅ | ✅ |
| View any transaction history | ❌ | ❌ | ✅ | ✅ | ✅ |
| View daily summary / volume | ❌ | ❌ | ✅ | ✅ | ✅ |
| **Payments** | | | | | |
| Single transfer | ✅ | ✅ | ❌ | ❌ | ❌ |
| Bulk salary/vendor payment | ❌ | ✅ | ❌ | ❌ | ❌ |
| View own payment status | ✅ | ✅ | ✅ | ✅ | ✅ |
| View any payment status | ❌ | ❌ | ✅ | ✅ | ✅ |
| **Notifications** | | | | | |
| View own notifications | ✅ | ✅ | ✅ | ✅ | ✅ |
| Trigger system notification | ❌ | ❌ | ✅ | ✅ | ❌ |
| **System** | | | | | |
| View service health | ❌ | ❌ | ❌ | ✅ | ❌ |
| Manage roles / users | ❌ | ❌ | ❌ | ✅ | ❌ |

---

## How Roles Map to Personas

| Persona | Role | Key Actions |
|---------|------|-------------|
| Priya (Retail Customer) | RETAIL | Register savings a/c, check balance, single transfer, view history |
| ABC Exports (Corporate) | BUSINESS | Register current a/c, single + bulk payments, view history |
| Rajesh (Ops Manager) | EMPLOYEE | View all accounts/txns, freeze accounts, daily summary |
| Lending Team | EMPLOYEE | View customer balances + history for credit assessment |
| RBI Auditor | AUDITOR | Read-only: all accounts, transactions, payments |

### RETAIL vs BUSINESS — Key Differences

| | RETAIL | BUSINESS |
|---|:---:|:---:|
| Account type | Savings only | Current only |
| Single transfer | ✅ | ✅ |
| Bulk payments (salary/vendor) | ❌ | ✅ |
| Multi-user (maker/checker) | ❌ | ❌ (Phase 2) |
| Overdraft facility | ❌ | ❌ (Phase 2) |

---

## Enforcement Points

| Layer | What It Checks |
|-------|---------------|
| **API Gateway** | Validates JWT. Extracts role. Rejects unauthenticated requests. |
| **Auth Service** | Issues JWT with `role` claim: `{ "sub": "user123", "role": "RETAIL" }` |
| **Each Service** | Checks role from JWT. `CUSTOMER` can only access own data. `EMPLOYEE`/`ADMIN`/`AUDITOR` can access all. |
| **Account Service** | `CUSTOMER` → only own accounts. `POST /transfer` → only `CUSTOMER`. |
| **Transaction Service** | `CUSTOMER` → only own history. Others → all history. |

---

## Rule: Own-Data Isolation

```
GET /api/accounts/user/{userId}
  → RETAIL / BUSINESS: userId must match JWT subject, else 403
  → EMPLOYEE / ADMIN / AUDITOR: any userId allowed

POST /api/payments/transfer
  → RETAIL / BUSINESS: from_account must belong to JWT subject
  → Others: not allowed

POST /api/payments/batch
  → BUSINESS only
```

---

## JWT Token Structure

```json
{
  "sub": "user_abc123",
  "email": "priya@email.com",
  "role": "RETAIL",
  "jti": "a1b2c3d4-e5f6-7890-abcd-ef1234567890",
  "iat": 1721289600,
  "exp": 1721293200
}

// or for business:
{
  "sub": "biz_xyz789",
  "email": "cfo@abcexports.com",
  "role": "BUSINESS",
  "jti": "z9y8x7w6-v5u4-3210-zyxw-vu9876543210",
  "iat": 1721289600,
  "exp": 1721293200
}
```

Services extract `role` from token. No additional DB lookup needed for basic authorization.

---

> This is MVP only. Phase 2 adds maker/checker roles for corporate, approval workflows, and finer-grained permissions.
