# 📋 Application Operations — What Each Role Can Do

> Catalog of all operations, organized by role. Tells you exactly what endpoints to hit and what happens.

---

## RETAIL — Priya (Individual Customer)

### 🔐 Authentication

| Operation | Endpoint | Notes |
|-----------|----------|-------|
| Register | `POST /api/auth/register` | Email + password. Auto-assigned `RETAIL` role. |
| Login | `POST /api/auth/login` | Returns JWT with `role: RETAIL` |
| Forgot password | `POST /api/auth/forgot-password` | Sends reset link (mock: console log) |
| Reset password | `POST /api/auth/reset-password` | Token from email, new password |

### 👤 Profile

| Operation | Endpoint | Notes |
|-----------|----------|-------|
| View my profile | `GET /api/accounts/profile` | Full name, phone, PAN, DOB, address |
| Edit my profile | `PUT /api/accounts/profile` | Update phone, address. PAN locked after first set. |

### 💰 Accounts — Can Open Up to 3

Each account needs a `label` to describe its purpose (e.g., "Primary Savings", "Emergency Fund", "ABC Exports Salary"). Labels must be unique per user.

| Operation | Endpoint | Notes |
|-----------|----------|-------|
| **Open savings account** | `POST /api/accounts` | `accountType: SAVINGS`, `label`, min ₹1,000 deposit. For personal use. |
| **Open salary account** | `POST /api/accounts` | `accountType: SALARY`, `label`, `employerBusinessId` required. Must be in that employer's list. Min ₹0 deposit. Zero-balance allowed. |
| View all my accounts | `GET /api/accounts/user/{userId}` | Returns all accounts owned by me (up to 3) |
| View one account | `GET /api/accounts/{accountId}` | Balance, type, label, status. Must be my account. |
| Close account | `POST /api/accounts/{accountId}/close` | Balance must be ₹0. Can't close last account. |

**Rules:** Max 3 accounts total (any mix of SAVINGS and SALARY). Max 1 SALARY account.

### 💸 Payments

| Operation | Endpoint | Notes |
|-----------|----------|-------|
| **Transfer money** | `POST /api/payments/transfer` | `fromAccount` must be mine. `Idempotency-Key` header prevents double-send. |
| View my payment history | `GET /api/payments?accountId={id}` | All transfers from/to my account |

### 📊 Transactions

| Operation | Endpoint | Notes |
|-----------|----------|-------|
| View my transaction history | `GET /api/transactions?accountId={id}&page=0&size=20` | Paginated, filtered by my account |
| View single transaction | `GET /api/transactions/{entryId}` | Must belong to my account |

### 🔔 Notifications

| Operation | Endpoint | Notes |
|-----------|----------|-------|
| View my notifications | `GET /api/notifications?userId={userId}` | SMS/email alerts. Auto-generated on transfers. |

### 🏢 Employer Link (Phase 2 — planned)

| Operation | Endpoint | Notes |
|-----------|----------|-------|
| View employer invitations | `GET /api/users/me/employer-invites` | Businesses that added me |
| Accept employer link | `POST /api/users/me/employers/{linkId}/accept` | Confirms I work there |
| View my employers | `GET /api/users/me/employers` | Active employer links |

---

## BUSINESS — ABC Exports (Corporate Customer)

### 🔐 Authentication

| Operation | Endpoint | Notes |
|-----------|----------|-------|
| Register | `POST /api/auth/register` | Email + password. Role specified as `BUSINESS`. |
| Login | `POST /api/auth/login` | Returns JWT with `role: BUSINESS` |
| Forgot password | `POST /api/auth/forgot-password` | — |
| Reset password | `POST /api/auth/reset-password` | — |

### 🏢 Business Profile

| Operation | Endpoint | Notes |
|-----------|----------|-------|
| Complete business profile | `POST /api/accounts/business-profile` | Company name, GST, PAN, business type, address |
| View business profile | `GET /api/accounts/business-profile` | — |
| Edit business profile | `PUT /api/accounts/business-profile` | Update contact info, address. GST/PAN locked. |

### 💰 Accounts — Unlimited, Each With a Purpose

Business can open as many CURRENT accounts as needed. Every account MUST have a `label` explaining its purpose — "Operations", "Payroll", "Export Revenue", "Vendor Payments", etc. Labels are unique per business. Add accounts during onboarding or any time after.

| Operation | Endpoint | Notes |
|-----------|----------|-------|
| **Open current account** | `POST /api/accounts` | `accountType: CURRENT`. `label` required (e.g., "Payroll", "Operations"). Must be unique per business. Min ₹10,000. |
| Open another account | `POST /api/accounts` | Same endpoint. New `label`. Add as many as you need, whenever you need. |
| View all my accounts | `GET /api/accounts/user/{userId}` | All CURRENT accounts with their labels and balances. |
| View one account | `GET /api/accounts/{accountId}` | Balance, label, type. Must be my account. |
| Close account | `POST /api/accounts/{accountId}/close` | Balance must be ₹0. Can close all accounts. |

**Example labels:** "Operations", "Payroll", "Export Revenue", "Vendor Payments", "Client Trust", "Surplus Funds", "Tax Reserve"

### 💸 Payments

| Operation | Endpoint | Notes |
|-----------|----------|-------|
| **Single transfer** | `POST /api/payments/transfer` | `fromAccount` must be mine. `Idempotency-Key` header. |
| **Bulk transfer** | `POST /api/payments/batch` | Multiple transfers in one call. `category` tag: `SALARY`, `VENDOR`, `DIVIDEND`, `REFUND`, `GENERAL`. |
| View payment history | `GET /api/payments?accountId={id}` | Filter by my account |

### 📊 Transactions

| Operation | Endpoint | Notes |
|-----------|----------|-------|
| View transaction history | `GET /api/transactions?accountId={id}&page=0&size=20` | Paginated. My accounts only. |
| View single transaction | `GET /api/transactions/{entryId}` | Must belong to my account |

### 👥 Employee Management

| Operation | Endpoint | Notes |
|-----------|----------|-------|
| **Add employees** | `POST /api/business/{bizId}/employees` | List of `{employeeUserId, employeeCode}`. Auto-accepted in MVP. |
| View employee list | `GET /api/business/{bizId}/employees` | All active + inactive employees |
| Remove employee | `DELETE /api/business/{bizId}/employees/{empId}` | Deactivates link. Their SALARY account converts to SAVINGS. |

### 🔔 Notifications

| Operation | Endpoint | Notes |
|-----------|----------|-------|
| View notifications | `GET /api/notifications?userId={userId}` | Transaction alerts, batch completion |

---

## EMPLOYEE — Rajesh (Bank Operations)

> Provisioned by ADMIN. Cannot self-register.

### 🔐 Authentication

| Operation | Endpoint | Notes |
|-----------|----------|-------|
| Login | `POST /api/auth/login` | Returns JWT with `role: EMPLOYEE` |

### 💰 Accounts (All-Access)

| Operation | Endpoint | Notes |
|-----------|----------|-------|
| View any account | `GET /api/accounts/{accountId}` | Any RETAIL or BUSINESS account |
| View all accounts | `GET /api/accounts?page=0&size=50` | Paginated list of all accounts in system |
| **Freeze account** | `PATCH /api/accounts/{id}/status` | Set status to `FROZEN`. Account cannot transact. |
| **Unfreeze account** | `PATCH /api/accounts/{id}/status` | Set status to `ACTIVE` |
| View accounts by user | `GET /api/accounts/user/{userId}` | See all accounts belonging to any user |

### 📊 Transactions (All-Access)

| Operation | Endpoint | Notes |
|-----------|----------|-------|
| View any transaction | `GET /api/transactions/{entryId}` | Any ledger entry in system |
| View all transactions | `GET /api/transactions?page=0&size=50` | System-wide transaction feed |
| **Daily summary** | `GET /api/transactions/daily-summary?date={date}` | Total count, total volume, by type |

### 💸 Payments (View-Only)

| Operation | Endpoint | Notes |
|-----------|----------|-------|
| View any payment | `GET /api/payments/{paymentId}` | Any payment status |
| View all payments | `GET /api/payments?page=0&size=50` | System-wide |

### 👥 Employee Management

| Operation | Endpoint | Notes |
|-----------|----------|-------|
| View any business's employees | `GET /api/business/{bizId}/employees` | For investigation purposes |

### 🔔 Notifications

| Operation | Endpoint | Notes |
|-----------|----------|-------|
| **Trigger system notification** | `POST /api/notifications` | Send SMS/email to user(s). E.g., maintenance alert. |

---

## ADMIN — System Administrator

> Has everything EMPLOYEE has, plus:

| Operation | Endpoint | Notes |
|-----------|----------|-------|
| **Provision user** | `POST /api/admin/users` | Create EMPLOYEE / ADMIN / AUDITOR accounts |
| **Change user role** | `PATCH /api/admin/users/{id}/role` | Promote/demote |
| **Deactivate user** | `PATCH /api/admin/users/{id}/status` | Disable login |
| View system health | `GET /actuator/health` | All services |
| View all users | `GET /api/admin/users` | System-wide user list |

---

## AUDITOR — RBI Auditor

> Read-only everything. Provisioned by ADMIN. No mutations.

| Operation | Endpoint | Notes |
|-----------|----------|-------|
| Login | `POST /api/auth/login` | Returns JWT with `role: AUDITOR` |
| View any account | `GET /api/accounts/{accountId}` | Balance, type, status, owner |
| View all accounts | `GET /api/accounts?page=0&size=100` | System-wide |
| View any transaction | `GET /api/transactions/{entryId}` | Full ledger entry |
| View all transactions | `GET /api/transactions?page=0&size=100` | Date-filtered |
| Daily summary | `GET /api/transactions/daily-summary?date={date}` | Aggregated |
| View any payment | `GET /api/payments/{paymentId}` | Payment details |
| View audit logs | `GET /api/admin/audit-logs?date={date}` | Who did what, when (Phase 2) |

---

## Operations Summary Matrix

| Operation | RETAIL | BUSINESS | EMPLOYEE | ADMIN | AUDITOR |
|-----------|:---:|:---:|:---:|:---:|:---:|
| Register self | ✅ | ✅ | ❌ | ❌ | ❌ |
| Login | ✅ | ✅ | ✅ | ✅ | ✅ |
| Reset own password | ✅ | ✅ | ✅ | ✅ | ❌ |
| View own profile | ✅ | ✅ | ✅ | ✅ | ✅ |
| Edit own profile | ✅ | ✅ | ❌ | ❌ | ❌ |
| Open account | ✅ (3 max) | ✅ (unlimited) | ❌ | ❌ | ❌ |
| View own accounts | ✅ | ✅ | ✅ | ✅ | ✅ |
| View any account | ❌ | ❌ | ✅ | ✅ | ✅ |
| Freeze/Unfreeze account | ❌ | ❌ | ✅ | ✅ | ❌ |
| Close own account | ✅ | ✅ | ❌ | ❌ | ❌ |
| Transfer money | ✅ | ✅ | ❌ | ❌ | ❌ |
| Bulk transfer | ❌ | ✅ | ❌ | ❌ | ❌ |
| View own payments | ✅ | ✅ | ✅ | ✅ | ✅ |
| View any payment | ❌ | ❌ | ✅ | ✅ | ✅ |
| View own transactions | ✅ | ✅ | ✅ | ✅ | ✅ |
| View any transaction | ❌ | ❌ | ✅ | ✅ | ✅ |
| Daily summary | ❌ | ❌ | ✅ | ✅ | ✅ |
| Add/Remove employees | ❌ | ✅ | ❌ | ❌ | ❌ |
| Trigger notification | ❌ | ❌ | ✅ | ✅ | ❌ |
| Manage users/roles | ❌ | ❌ | ❌ | ✅ | ❌ |
| View system health | ❌ | ❌ | ❌ | ✅ | ❌ |
| View audit logs | ❌ | ❌ | ❌ | ✅ | ✅ |

---

> **Note:** Where an operation says "own" — the JWT `sub` must match the resource owner. EMPLOYEE/ADMIN/AUDITOR bypass this check. See `ROLES.md` for enforcement rules.
