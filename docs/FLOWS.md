# 🔄 System Flows — Index

> Each service has its own FLOWS.md with exact step-by-step instructions.
> No tables. No pipes. Directly translatable to code.

---

## Per-Service Flow Files

| Service | File | Contains |
|---------|------|----------|
| Auth | `auth-service/FLOWS.md` | Register, login, logout, refresh, forgot/reset password, admin user management, token validation for Gateway |
| Account | `account-service/FLOWS.md` | Create accounts (SAVINGS/SALARY/CURRENT), atomic transfer, close, freeze/unfreeze, business profiles, employee management |
| Payment | `payment-service/FLOWS.md` | Single transfer, bulk transfer, outbox pollers (1s + 10s), Kafka confirmation consumer, retry scheduler, cleanup |
| Transaction | `transaction-service/FLOWS.md` | Kafka ledger batch consumer, transaction history queries (filtered + paginated), daily summary |
| Notification | `notification-service/FLOWS.md` | Kafka payment-events consumer, SMS template rendering, notification history, manual trigger |

---

## Kafka Topics

| Topic | Publisher | Consumer | Trigger |
|-------|-----------|----------|---------|
| `payment-events` | Payment (1s poller) | Notification | Every transfer |
| `ledger-batches` | Payment (10s poller) | Transaction | Every transfer |
| `ledger-confirm` | Transaction | Payment | After batch INSERT |

---

## Inter-Service Sync Calls

| Caller | Callee | Endpoint | When |
|--------|--------|----------|------|
| Gateway | Auth | `GET /api/auth/validate` | Every request (JWT validation) |
| Payment | Account | `POST /api/accounts/transfer` | Every transfer |
| Payment | Account | `GET /api/accounts/{id}` | Ownership verification |
| Account | Auth | `GET /api/auth/users/{id}` | Verify user exists (employee add, salary account) |
| Transaction | Account | `GET /api/accounts/{id}` | Ownership verification for queries |
| Notification | Account | `GET /api/accounts/{id}` | Get sender/receiver details |
| Notification | Auth | `GET /api/auth/users/{id}` | Get user phone/email |

---

## Quick Reference: All 37 Flows

```
RETAIL (19 flows):
  F-R01  Register              → auth-service/FLOWS.md
  F-R02  Complete Profile      → account-service/FLOWS.md
  F-R03  Open Account          → account-service/FLOWS.md
  F-R04  Login                 → auth-service/FLOWS.md
  F-R05  Check Balance         → account-service/FLOWS.md
  F-R06  List My Accounts      → account-service/FLOWS.md
  F-R07  Single Transfer       → payment-service/FLOWS.md
  F-R08  Outbox: Notifications → payment-service/FLOWS.md (system)
  F-R09  Outbox: Ledger        → payment-service + transaction-service
  F-R10  Outbox: Error Path    → payment-service + transaction-service
  F-R11  Outbox: Retry         → payment-service/FLOWS.md (system)
  F-R12  View Transactions     → transaction-service/FLOWS.md
  F-R13  Filtered Transactions → transaction-service/FLOWS.md
  F-R14  View Notifications    → notification-service/FLOWS.md
  F-R15  Forgot Password       → auth-service/FLOWS.md
  F-R16  Reset Password        → auth-service/FLOWS.md
  F-R17  Logout                → auth-service/FLOWS.md
  F-R18  Open Salary Account   → account-service/FLOWS.md
  F-R19  Close Account         → account-service/FLOWS.md

BUSINESS (9 flows):
  F-B01  Register              → auth-service/FLOWS.md
  F-B02  Business Profile      → account-service/FLOWS.md
  F-B03  Open Current Account  → account-service/FLOWS.md
  F-B04  Single Transfer       → payment-service/FLOWS.md
  F-B05  Bulk Transfer         → payment-service/FLOWS.md
  F-B06  Add Employees         → account-service/FLOWS.md
  F-B07  Remove Employee       → account-service/FLOWS.md
  F-B08  List Employees        → account-service/FLOWS.md
  F-B09  Close Account         → account-service/FLOWS.md

EMPLOYEE (8 flows):
  F-E01  Login                 → auth-service/FLOWS.md
  F-E02  View Any Account      → account-service/FLOWS.md
  F-E03  Search by Account#    → account-service/FLOWS.md
  F-E04  Freeze Account        → account-service/FLOWS.md
  F-E05  Unfreeze Account      → account-service/FLOWS.md
  F-E06  Daily Summary         → transaction-service/FLOWS.md
  F-E07  All Transactions      → transaction-service/FLOWS.md
  F-E08  Trigger Notification  → notification-service/FLOWS.md

ADMIN (4 flows on top of EMPLOYEE):
  F-A01  Provision User        → auth-service/FLOWS.md
  F-A02  Change Role           → auth-service/FLOWS.md
  F-A03  Deactivate User       → auth-service/FLOWS.md
  F-A04  System Health         → Gateway (Spring Actuator)

AUDITOR (1 flow + EMPLOYEE read-only equivalents):
  F-AU01 Login                 → auth-service/FLOWS.md
  (all EMPLOYEE view operations, no mutations)

SYSTEM:
  Token Validation (every req) → Gateway → Auth
  Outbox SENDING Reaper (5min) → payment-service/FLOWS.md
  Outbox Cleanup (hourly)      → payment-service/FLOWS.md
  Blacklist Cleanup (hourly)   → auth-service/FLOWS.md
