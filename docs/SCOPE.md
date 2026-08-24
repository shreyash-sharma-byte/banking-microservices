# 🏦 Banking Microservices Platform — Scope Document (Lean)

> **Version:** 3.0  
> **Date:** 2026-08-05  
> **Principle:** **YAGNI** — You Ain't Gonna Need It. Build only what's needed NOW.  
> No abstraction "for the future." No interface with one implementation.  
> No factory for a single class. Solve today's problem with today's code.



---

## The Goal

Build **5 core services** that work together to simulate a real banking backend. Every service teaches at least one distributed-systems concept. A 6th service (API Gateway) ties them together.

---

## The Services (What We'll Actually Build)

| # | Service | What It Does | Teaches |
|---|---------|-------------|---------|
| 1 | **Auth Service** | Register, login, JWT tokens, role-based access (CUSTOMER / EMPLOYEE / ADMIN) | Security, stateless auth, inter-service token validation |
| 2 | **Account Service** | Create accounts, check balance, freeze/unfreeze, **atomic transfer** (debit+credit in one DB transaction) | Database-per-service, ACID within a service boundary |
| 3 | **Transaction Service** | Consume batched ledger events from Kafka, maintain unified append-only ledger, serve history | Event sourcing, bulk INSERT, idempotent consumption |
| 4 | **Payment Service** | Validate transfer, call Account.transfer() (atomic), manage outbox for ledger + notification events | Idempotency, Outbox Pattern, batch sync with confirmation |
| 5 | **Notification Service** | Send email/SMS alerts when a transaction happens | **Async messaging (Kafka)**, pub/sub, event-driven |
| 6 | **API Gateway** | Single entry point, routes requests, enforces auth, rate limiting | Gateway pattern, routing, cross-cutting concerns |

---

## What Each Persona Can Do (Trimmed)

### Priya — Retail Customer

| # | Action | Hits These Services |
|---|--------|---------------------|
| 1 | Register an account & log in | Auth → Account |
| 2 | Check balance of my accounts | Account |
| 3 | Transfer money to another account | Payment → Account (atomic transfer, 1 REST call) → Outbox → Kafka → Transaction + Notification |
| 4 | View my last N transactions | Transaction |
| 5 | Get SMS/email when money is sent/received | Notification (triggered by Kafka event) |

### Rajesh — Bank Operations

| # | Action | Hits These Services |
|---|--------|---------------------|
| 1 | View all transactions in the system (admin) | Transaction |
| 2 | Freeze a suspicious account | Account |
| 3 | See a dashboard of daily transaction volume | Transaction (aggregated query) |

### Lending Team — Loan Officer

| # | Action | Hits These Services |
|---|--------|---------------------|
| 1 | Check a customer's balance & transaction history (credit assessment) | Account + Transaction |
| 2 | Approve a loan → disburse to customer's account | Payment (disbursement — same transfer flow) |

### ABC Exports — Corporate

| # | Action | Hits These Services |
|---|--------|---------------------|
| 1 | Multi-user access with roles (maker/checker) | Auth (RBAC) |
| 2 | Bulk salary disbursement (batch transfer) | Payment (batch processing) |
| 3 | View consolidated transaction report | Transaction |

### RBI Auditor

| # | Action | Hits These Services |
|---|--------|---------------------|
| 1 | Pull immutable transaction logs for audit period | Transaction |
| 2 | Verify KYC status of sampled accounts | Account (via Auth) |
| 3 | Check that no transaction was ever deleted/modified | Transaction (append-only proof) |

---

## Architecture Diagram

```
                    ┌──────────────────────┐
                    │     API Gateway       │
                    │   (auth, routing,     │
                    │    rate limiting)     │
                    └──────┬───────┬───────┘
                           │       │
           ┌───────────────┼───────┼───────────────────────────┐
           │               │       │                           │
    ┌──────▼──────┐  ┌─────▼──┐ ┌──▼────────────┐ ┌───────────▼──────┐
    │    Auth     │  │Account │ │   Payment     │ │  Transaction    │
    │   Service   │  │Service │ │   Service     │ │   Service       │
    │             │  │        │ │(orchestrator) │ │ (append-only    │
    │ JWT + RBAC  │  │balance │ │idempotency│ │  unified ledger)│
    └─────────────┘  │transfer│ │outbox mgmt│ └────────▲────┬───┘
                     └───┬────┘ │┌─────────┐│          │    │
                         │      ││ Outbox  ││          │    │
                         │      ││PENDING→ ││          │    │
                         │      ││SENDING→│─┼──┐       │    │
                         │      ││PUBLISHED││  │       │    │
                         │      ││ ERROR  ││  │       │    │
                         │      │└─────────┘│  │       │    │
                         │      └─────┬─────┘  │       │    │
                         │            │        │       │    │
                   1 REST│   Notif:1s │Ledger: │       │    │
                    call │   Ledger:  │  10s   │       │    │
                         │      10s   │        │       │    │
                         │      ┌─────▼────────▼──┐    │    │
                         │      │      Kafka      │    │    │
                         └──────┤ payment-events  │    │    │
                                │ ledger-batches  ├────┘    │
                                │ ledger-confirm  ├─────────┘
                                 └──────┬──────┘
                                        │
                                 ┌──────▼──────┐
                                 │ Notification│
                                 │   Service   │
                                 │ (listens to │
                                 │ payment-    │
                                 │  events)    │
                                 └─────────────┘

  ┌─────────────────────────────────────────────────────────────┐
  │       Cross-Cutting: Eureka(Discovery) | Config Server      │
  │              Zipkin(Tracing) | Resilience4j(CB)             │
  └─────────────────────────────────────────────────────────────┘
```

---

## Key Patterns This Project Teaches

| Pattern | Where | Why |
|---------|-------|-----|
| **Database per Service** | Every service has its own PostgreSQL | No shared DB = true microservices |
| **Atomic Transfer** | Account Service: `UPDATE from SET balance=balance-? WHERE balance>=?` in one DB txn | ACID within a service boundary. 1 REST call, not 2. |
| **Outbox Pattern** | Payment Service writes ledger + notification events to local `outbox` in same DB txn | Guarantees delivery — no dual-write problem |
| **Dual Schedulers** | 1s poller (notifications → `payment-events`), 10s poller (ledger → `ledger-batches`) | Notifications need speed. Ledger can wait. |
| **4-State Machine** | Outbox: `PENDING → SENDING → PUBLISHED/ERROR`. SKIP LOCKED for concurrency. | No lost entries. Multi-instance safe. Debuggable. |
| **Error Recovery** | Retry scheduler resets ERROR→PENDING if retry_count<3. ERROR entries never auto-deleted. | No data loss. Ops can investigate stuck ERROR entries. |
| **Event Sourcing** | Transaction Service stores immutable events from all services | Unified audit trail, single source of truth |
| **Circuit Breaker** | Payment calls Account Service via Resilience4j | If Account is down, Payment fails gracefully |
| **Service Discovery** | Eureka — services register and find each other | No hardcoded URLs |
| **Centralized Config** | Spring Cloud Config — one place for all service configs | Change config without redeploy |
| **API Gateway** | Single entry, routes to services, validates JWT | Security + routing in one place |
| **Idempotency** | Payment: `Idempotency-Key` header. Transaction Svc: `batch_id` dedup. | Same request twice = processed once |
| **Correlation IDs** | `X-Correlation-Id` passed through every service call + logs | Trace a request across 5 services |
| **Saga** | ⏳ Phase 2 — Loan disbursement (loan_db → account_db, genuine cross-DB) | Taught on the RIGHT use case |

---

## Tech Stack

| Layer | Choice |
|-------|--------|
| Language + Framework | Java 17 + Spring Boot 3 |
| Database (per service) | PostgreSQL |
| Message Broker | Apache Kafka |
| Service Discovery | Netflix Eureka |
| Config Server | Spring Cloud Config |
| Circuit Breaker | Resilience4j |
| Tracing | Micrometer + Zipkin |
| Containerization | Docker + Docker Compose |
| API Docs | SpringDoc OpenAPI (Swagger) |

---

## Database Tables (Per Service — Only What's Needed)

### Auth Service DB (`auth_db`)
```sql
users (id, email, password_hash, role, created_at)
```

### Account Service DB (`account_db`)
```sql
accounts (id, user_id, account_number, account_type, balance, status, created_at)
```

### Transaction Service DB (`transaction_db`) — Append Only
```sql
ledger_entries (id, entry_id, account_id, type [DEBIT|CREDIT], amount, 
                reference, description, created_at)
-- entry_id links the debit and credit of the same transaction (double-entry)
-- NO UPDATEs, NO DELETEs — only INSERT + SELECT
```

### Payment Service DB (`payment_db`)
```sql
payments (id, idempotency_key, from_account, to_account, amount, 
          status [PENDING|COMPLETED|FAILED], created_at)
```

### Notification Service DB (`notification_db`)
```sql
notifications (id, user_id, type [SMS|EMAIL], message, status [SENT|FAILED], 
               triggered_by_txn_id, created_at)
```

---

## Core API Endpoints

### Auth Service
```
POST   /api/auth/register          — Register new user
POST   /api/auth/login             — Login, returns JWT
GET    /api/auth/validate          — Internal: validate token (called by Gateway)
GET    /api/auth/me                — Get current user info from token
```

### Account Service
```
POST   /api/accounts               — Create new account
GET    /api/accounts/{id}          — Get account details + balance
GET    /api/accounts/user/{userId} — List all accounts of a user
POST   /api/accounts/transfer      — Atomic transfer (debit+credit in 1 DB txn)
PATCH  /api/accounts/{id}/status   — Freeze/unfreeze (ADMIN only)
```

### Transaction Service
```
GET    /api/transactions?accountId=X&page=0&size=20    — Paginated history
GET    /api/transactions/{entryId}                      — Single ledger entry
GET    /api/transactions/daily-summary?date=YYYY-MM-DD  — Daily volume (ADMIN)
POST   /api/transactions            — Internal: Record entry (called by Payment Svc)
```

### Payment Service
```
POST   /api/payments/transfer      — Transfer money (header: Idempotency-Key)
GET    /api/payments/{id}          — Get payment status
POST   /api/payments/batch         — Bulk salary disbursement
GET    /api/payments?accountId=X   — Payment history for account
```

### Notification Service
```
GET    /api/notifications?userId=X — User's notification history
POST   /api/notifications          — Internal: trigger notification
```

---

## The Core Flow: Money Transfer (Atomic + Outbox + Batch Sync)

```
1. Client → POST /api/payments/transfer
   Headers: Authorization, Idempotency-Key, X-Correlation-Id

2. Gateway → Auth Service: validate JWT → OK

3. Gateway → Payment Service: forward request

4. Payment Service:
   ┌─────────────────────────────────────────────────────────┐
   │ a. Check idempotency (Idempotency-Key) → not duplicate │
   │                                                          │
   │ b. Call Account Svc: POST /accounts/transfer            │
   │    Account Service internally (ONE DB transaction):     │
   │      UPDATE from_acc SET balance = balance - ?          │
   │        WHERE id=? AND balance >= ?                      │
   │      UPDATE to_acc SET balance = balance + ?            │
   │        WHERE id=?                                       │
   │      INSERT INTO audit_log (...)                        │
   │    → Rows affected=0 for debit → insufficient balance   │
   │    → All ACID. Nothing to compensate. 1 REST call.      │
   │                                                          │
   │ c. LOCAL DB TRANSACTION (ACID):                         │
   │    INSERT INTO payments (status=COMPLETED, ...)         │
   │    INSERT INTO outbox (PENDING, LedgerEntry, DEBIT)     │
   │    INSERT INTO outbox (PENDING, LedgerEntry, CREDIT)    │
   │    INSERT INTO outbox (PENDING, NotificationRequired)   │
   │                                                          │
   │ d. Return SUCCESS to client                             │
   └─────────────────────────────────────────────────────────┘

5. Outbox Schedulers (Payment Service, separate threads):
   ┌─────────────────────────────────────────────────────────┐
   │ ⚡ 1s scheduler: NotificationRequired → "payment-events"│
   │ 🐢 10s scheduler: LedgerEntry → "ledger-batches"        │
   │ Both use SELECT ... FOR UPDATE SKIP LOCKED              │
   │ (safe with multiple Payment Service instances)          │
   └─────────────────────────────────────────────────────────┘

6. Transaction Service → Notification Service
   (full confirmation + error paths in ADR.md)
```

### Outbox State Machine

```
                    ┌──────────────────────────────────┐
                    │                                  │
                    ▼                                  │
   ┌─────────┐  scheduler  ┌─────────┐  COMMITTED  ┌──┴───────┐  cleanup  ┌─────────┐
   │ PENDING │────────────►│ SENDING │────────────►│PUBLISHED │──────────►│ DELETED │
   │         │  (1s or 10s)│         │              │          │  (hourly) │         │
   └─────────┘             └────┬────┘              └──────────┘           └─────────┘
                                │
                                │ FAILED
                                ▼
                           ┌─────────┐
                           │  ERROR  │
                           │ NEVER   │──► retry_count < 3? → reset to PENDING (30s)
                           │ auto-   │
                           │ deleted │──► retry_count >= 3? → stuck, manual review
                           └─────────┘
```

> ⏳ **Saga pattern** is deferred to Phase 2 (Loan disbursement: loan_db → account_db — genuine cross-DB distributed transaction).

---

## MVP Checklist (Phase 1 — Build This First)

- [ ] Project scaffolding: 5 services + config server + eureka + gateway
- [ ] Docker Compose: PostgreSQL × 4 + Kafka + Zookeeper + Zipkin
- [ ] Auth Service: register, login, JWT generation & validation
- [ ] API Gateway: route requests, enforce JWT, basic rate limiting
- [ ] Account Service: create account, get balance, **atomic transfer**, freeze/unfreeze
- [ ] Payment Service: single transfer (calls Account atomic transfer), outbox table, dual schedulers (1s/10s)
- [ ] Transaction Service: Kafka consumer for `ledger-batches`, bulk INSERT, unified ledger queries
- [ ] Notification Service: Kafka consumer for `payment-events`, log notifications (mock SMS/email)
- [ ] Postman collection for all endpoints

---

## Database Tables (Per Service)

> **Authoritative schemas in each service's `DATABASE.md`.**
> Summary: 5 databases, 14 tables. Partitioned: `unified_ledger`, `audit_log`.

| Service | DB | Key Tables |
|---------|----|-----------|
| Auth | `auth_db` | `users`, `refresh_tokens`, `token_blacklist` |
| Account | `account_db` | `retail_profiles`, `business_profiles`, `accounts`, `audit_log`, `business_employees` |
| Payment | `payment_db` | `payments`, `outbox`, `processed_batches` |
| Transaction | `transaction_db` | `unified_ledger` (partitioned), `processed_batches` |
| Notification | `notification_db` | `notifications` |

---

## What We're NOT Building

| Skipped | Why |
|---------|-----|
| UPI, NEFT/RTGS specifics | Just "transfer" — the pattern is what matters |
| Bill payments, credit cards | Extra services, same patterns repeat |
| Loan origination, EMI, collections | Large separate domain |
| Fraud detection ML / rule engine | Too complex for MVP |
| Forex, multi-currency | Adds noise, not core learning |
| Frontend (mobile/web UI) | Backend API project — test with Postman/Swagger |
| Real SMS/email provider | `System.out.println` — replace later |
| Kubernetes deployment | Docker Compose is enough for learning |
| Real bank integrations | Mock everything external |

---

## Project File Structure

```
BankingMicroservices/
├── docker-compose.yml
├── config-server/              # Spring Cloud Config
├── eureka-server/              # Service Discovery
├── api-gateway/                # Spring Cloud Gateway
├── auth-service/
│   └── src/main/java/com/bank/auth/
│       ├── AuthServiceApplication.java
│       ├── controller/AuthController.java
│       ├── service/AuthService.java
│       ├── model/User.java
│       ├── repository/UserRepository.java
│       ├── security/JwtUtil.java
│       └── config/SecurityConfig.java
├── account-service/
│   └── src/main/java/com/bank/account/
│       ├── controller/, service/, model/, repository/
├── transaction-service/
│   └── src/main/java/com/bank/transaction/
├── payment-service/
│   └── src/main/java/com/bank/payment/
├── notification-service/
│   └── src/main/java/com/bank/notification/
└── postman/
    └── BankingMicroservices.postman_collection.json
```

---

**Ready? Confirm the tech stack (Java + Spring Boot recommended) and I'll scaffold the entire project.**

---

> 📋 **Future Scope:** See [`FUTURE_SCOPE.md`](./FUTURE_SCOPE.md) for the full Phase 2→3→4 roadmap — Card Service, Loan Service, Fraud Detection, Reporting, Partner APIs, K8s deployment, and everything else we'll build after MVP.
> 
> 📐 **Architecture Decisions:** See [`ADR.md`](./ADR.md) for why we chose Outbox + Batch Sync, separate Payment & Transaction services, dual Kafka topics, and other key design decisions.

