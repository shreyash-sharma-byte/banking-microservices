# 🛠️ Build Log — How We Built This

> A chronological record of every step, every file, and every decision during scaffolding.  
> Use this to explain the process to anyone.

---

## Phase 0: Planning (July 18-23, 2026)

| Step | What We Did | Files Created |
|------|-------------|---------------|
| 1 | Defined project scope — 5 core services, what each teaches | `docs/SCOPE.md` |
| 2 | Mapped future phases (Phase 2-4: Loan, Card, Fraud, K8s) | `docs/FUTURE_SCOPE.md` |
| 3 | Documented 8 architecture decisions (Outbox, batch sync, no Saga in MVP, etc.) | `docs/ADR.md` |
| 4 | Defined 5 user roles with permissions matrix | `docs/ROLES.md` |
| 5 | Designed onboarding flows for RETAIL + BUSINESS customers | `docs/ONBOARDING.md` |
| 6 | Catalogued every operation every role can perform | `docs/OPERATIONS.md` |
| 7 | Mapped 37 flows across all services | `docs/FLOWS.md` + 5 per-service `FLOWS.md` |
| 8 | Designed 14 tables across 5 databases | 5 per-service `DATABASE.md` files |

**Principle:** YAGNI — build only what's needed today. No interfaces with one impl. No factories for single classes.

---

## Phase 1: Infrastructure Scaffolding (Aug 5, 2026)

### Step 1: Docker Compose
**File:** `docker-compose.yml`  
**What:** 8 containers — PostgreSQL × 5 (auth, account, payment, transaction, notification DBs), Zookeeper, Kafka, Zipkin.  
**Why:** Database-per-service pattern. Kafka for async event bus. Zipkin for distributed tracing.

### Step 2: Maven Parent POM
**File:** `pom.xml` (root)  
**What:** Spring Boot 3.3.2 parent, Spring Cloud 2023.0.3, Java 17. Common deps: Lombok, Actuator, Test.  
**Modules:** config-server, eureka-server, api-gateway, auth-service, account-service, payment-service, transaction-service, notification-service.

### Step 3: Config Server
**Files:**
- `config-server/pom.xml` — spring-cloud-config-server
- `config-server/src/main/java/.../ConfigServerApplication.java` — `@EnableConfigServer`
- `config-server/src/main/resources/application.yml` — port 8888, native profile
- `config-server/src/main/resources/config/application.yml` — shared Eureka + Zipkin + logging config
- `config-server/src/main/resources/config/auth-service.yml` — port 8081, auth_db, JWT secrets
- `config-server/src/main/resources/config/account-service.yml` — port 8082, account_db
- `config-server/src/main/resources/config/payment-service.yml` — port 8083, payment_db, Kafka producer+consumer
- `config-server/src/main/resources/config/transaction-service.yml` — port 8084, transaction_db, Kafka consumer
- `config-server/src/main/resources/config/notification-service.yml` — port 8085, notification_db, Kafka consumer
- `config-server/src/main/resources/config/api-gateway.yml` — port 8080, route definitions

**Why native, not git:** YAGNI. No need for a git repo for local dev. Filesystem backend.

### Step 4: Eureka Server
**Files:**
- `eureka-server/pom.xml` — spring-cloud-starter-netflix-eureka-server
- `eureka-server/src/main/java/.../EurekaServerApplication.java` — `@EnableEurekaServer`
- `eureka-server/src/main/resources/application.yml` — port 8761, no self-registration

**Why:** Dynamic service discovery. Services register by name, Gateway routes by name. No hardcoded URLs.

### Step 5: API Gateway
**Files:**
- `api-gateway/pom.xml` — spring-cloud-starter-gateway, eureka-client, config-client, jjwt
- `api-gateway/src/main/java/.../GatewayApplication.java`
- `api-gateway/src/main/java/.../JwtAuthFilter.java` — GlobalFilter, validates JWT on every request
- `api-gateway/src/main/resources/application.yml` — imports config from config-server

**JwtAuthFilter logic:**
1. Generate X-Correlation-Id if missing (UUID)
2. Public paths (/api/auth/register, /login, /forgot-password, /reset-password, /refresh) → pass through with correlation ID only
3. Protected paths → extract Bearer token → validate with jjwt → extract sub + role → set X-User-Id, X-User-Role headers
4. Invalid/expired token → 401

### Step 6: Auth Service
**Files:**
- `auth-service/pom.xml` — web, jpa, eureka, config, postgres, jjwt, spring-security-crypto
- `auth-service/src/main/java/.../AuthServiceApplication.java`
- `auth-service/src/main/java/.../model/User.java` — id, email, passwordHash, role (RETAIL|BUSINESS|EMPLOYEE|ADMIN|AUDITOR), status (ACTIVE|INACTIVE|LOCKED)
- `auth-service/src/main/java/.../model/RefreshToken.java` — tokenHash (SHA-256), expiresAt, revoked
- `auth-service/src/main/java/.../model/TokenBlacklist.java` — jti (JWT ID), expiresAt
- `auth-service/src/main/java/.../repository/UserRepository.java`
- `auth-service/src/main/java/.../repository/RefreshTokenRepository.java`
- `auth-service/src/main/java/.../repository/TokenBlacklistRepository.java`
- `auth-service/src/main/java/.../security/JwtUtil.java` — generate access token (jjwt, sub + role + jti, 1h), generate refresh token (UUID×2, 7d), parse/validate
- `auth-service/src/main/java/.../controller/AuthController.java` — register, login, logout, refresh, forgot-password, reset-password, validate (internal)
- `auth-service/src/main/java/.../controller/AdminController.java` — provisionUser, changeRole, changeStatus, listUsers
- `auth-service/src/main/resources/application.yml` — config import, sql init
- `auth-service/src/main/resources/schema.sql` — CREATE TABLE users, refresh_tokens, token_blacklist + indexes

**Key decisions:**
- bcrypt for password hashing (spring-security-crypto)
- JWT with jti claim for blacklist-based logout
- Refresh token rotation (old token revoked, new issued on each refresh)
- SHA-256 hashed refresh tokens in DB (raw token never stored)
- Mock email for password reset (System.out.println)

### Step 7: Account Service (in progress)
**Files so far:**
- `account-service/pom.xml`
- `account-service/src/main/java/.../AccountServiceApplication.java`
- `account-service/src/main/java/.../model/RetailProfile.java`
- `account-service/src/main/java/.../model/BusinessProfile.java`
- `account-service/src/main/java/.../model/Account.java` — ownerId, accountNumber, accountType (SAVINGS|SALARY|CURRENT), label, balance, employerBusinessId, status
- `account-service/src/main/java/.../model/BusinessEmployee.java`
- `account-service/src/main/java/.../model/AuditLog.java`
- `account-service/src/main/java/.../repository/AccountRepository.java`
- `account-service/src/main/java/.../repository/RetailProfileRepository.java`

**Still needed:** BusinessProfileRepo, BusinessEmployeeRepo, AuditLogRepo, AccountController (with atomic transfer), BusinessController, schema.sql, application.yml

---

## Architecture: What Talks to What

```
Client → API Gateway (8080)
           │ JWT validation
           │ Correlation ID
           │ Route by path
           ▼
    ┌──────┼──────┬──────────┬──────────┐
    ▼      ▼      ▼          ▼          ▼
   Auth  Account Payment Transaction Notification
   8081   8082    8083       8084       8085

Sync calls:
  Gateway → Auth (validate JWT)
  Payment → Account (POST /transfer — atomic)
  Account → Auth (GET /users/{id} — verify user exists)
  Transaction → Account (GET /accounts/{id} — ownership check)
  Notification → Account (GET /accounts/{id}) + Auth (GET /users/{id})

Kafka:
  Payment ──payment-events──→ Notification (1s poller)
  Payment ──ledger-batches──→ Transaction (10s poller)
  Transaction ──ledger-confirm──→ Payment (after bulk INSERT)
```

---

> **Next:** Complete Account Service, then Payment → Transaction → Notification.
