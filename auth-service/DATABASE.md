# 🔐 Auth Service — Database Design

> **Database:** `auth_db`  
> **Purpose:** Store user credentials, roles, and session data. This is the only service that stores passwords.

---

## ER Diagram (Logical)

```
┌──────────────────────┐
│        users         │
├──────────────────────┤
│ id          PK UUID  │
│ email       UNIQUE   │
│ password_hash        │
│ role                 │
│ status               │
│ created_at           │
│ updated_at           │
└──────────┬───────────┘
           │ 1
           │
           │ has
           │
           ▼ N
┌──────────────────────┐
│    refresh_tokens    │
├──────────────────────┤
│ id          PK UUID  │
│ user_id     FK ──────┤──► users.id
│ token_hash           │
│ expires_at           │
│ revoked              │
│ created_at           │
└──────────────────────┘

┌──────────────────────┐
│   token_blacklist    │
├──────────────────────┤
│ id          PK UUID  │
│ jti                  │  (JWT ID — unique per token)
│ expires_at           │
│ created_at           │
└──────────────────────┘
```

---

## Tables

### `users`

Every person or entity that authenticates with the system.

| Column | Type | Constraints | Notes |
|--------|------|-------------|-------|
| `id` | UUID | PK | Generated at registration |
| `email` | VARCHAR(255) | UNIQUE, NOT NULL | Used as login identifier |
| `password_hash` | VARCHAR(255) | NOT NULL | bcrypt hashed |
| `role` | VARCHAR(20) | NOT NULL, CHECK | `RETAIL`, `BUSINESS`, `EMPLOYEE`, `ADMIN`, `AUDITOR` |
| `status` | VARCHAR(20) | DEFAULT 'ACTIVE' | `ACTIVE`, `INACTIVE`, `LOCKED` |
| `created_at` | TIMESTAMP | DEFAULT NOW() | |
| `updated_at` | TIMESTAMP | DEFAULT NOW() | Updated on profile/role changes |

```sql
CREATE TABLE users (
    id              UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    email           VARCHAR(255) UNIQUE NOT NULL,
    password_hash   VARCHAR(255) NOT NULL,
    role            VARCHAR(20) NOT NULL CHECK (role IN ('RETAIL','BUSINESS','EMPLOYEE','ADMIN','AUDITOR')),
    status          VARCHAR(20) DEFAULT 'ACTIVE' CHECK (status IN ('ACTIVE','INACTIVE','LOCKED')),
    created_at      TIMESTAMP DEFAULT NOW(),
    updated_at      TIMESTAMP DEFAULT NOW()
);

CREATE INDEX idx_users_email ON users(email);
CREATE INDEX idx_users_role ON users(role);
```

### `refresh_tokens`

Long-lived tokens that let users get a new JWT without re-logging in.

| Column | Type | Constraints | Notes |
|--------|------|-------------|-------|
| `id` | UUID | PK | |
| `user_id` | UUID | NOT NULL, FK | References `users.id` (application-level FK) |
| `token_hash` | VARCHAR(255) | UNIQUE, NOT NULL | SHA-256 of the refresh token |
| `expires_at` | TIMESTAMP | NOT NULL | 7 days from issue |
| `revoked` | BOOLEAN | DEFAULT FALSE | TRUE if user logged out or token rotated |
| `created_at` | TIMESTAMP | DEFAULT NOW() | |

```sql
CREATE TABLE refresh_tokens (
    id              UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id         UUID NOT NULL,
    token_hash      VARCHAR(255) UNIQUE NOT NULL,
    expires_at      TIMESTAMP NOT NULL,
    revoked         BOOLEAN DEFAULT FALSE,
    created_at      TIMESTAMP DEFAULT NOW()
);

CREATE INDEX idx_refresh_user ON refresh_tokens(user_id);
CREATE INDEX idx_refresh_hash ON refresh_tokens(token_hash);
```

### `token_blacklist`

JWTs that were explicitly invalidated (logout, password change). Short-lived entries — cleaned up after expiry.

| Column | Type | Constraints | Notes |
|--------|------|-------------|-------|
| `id` | UUID | PK | |
| `jti` | VARCHAR(255) | UNIQUE, NOT NULL | JWT ID claim extracted from token |
| `expires_at` | TIMESTAMP | NOT NULL | When the original JWT expires (TTL for this row) |
| `created_at` | TIMESTAMP | DEFAULT NOW() | |

```sql
CREATE TABLE token_blacklist (
    id              UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    jti             VARCHAR(255) UNIQUE NOT NULL,
    expires_at      TIMESTAMP NOT NULL,
    created_at      TIMESTAMP DEFAULT NOW()
);

CREATE INDEX idx_blacklist_jti ON token_blacklist(jti);
CREATE INDEX idx_blacklist_expires ON token_blacklist(expires_at);
```

---

## Cleanup Jobs

```sql
-- Run every hour: remove expired blacklist entries
DELETE FROM token_blacklist WHERE expires_at < NOW();

-- Run every hour: remove expired/revoked refresh tokens older than 7 days
DELETE FROM refresh_tokens WHERE (expires_at < NOW() OR revoked = TRUE) AND created_at < NOW() - INTERVAL '7 days';
```

---

## What Auth Service Stores vs Doesn't Store

| Stored Here | Stored in Account Service |
|-------------|--------------------------|
| ✅ email, password_hash, role | ❌ |
| ✅ JWT blacklist | ❌ |
| ❌ fullName, phone, DOB, PAN | ✅ (retail_profiles) |
| ❌ companyName, GST, businessType | ✅ (business_profiles) |
| ❌ account numbers, balances | ✅ (accounts) |

---

## Cross-Service Relationships

```
Auth DB                          Account DB
users(id=biz_xyz)  ── REST ──►  accounts(owner_id=biz_xyz)
users(id=usr_abc)  ── REST ──►  retail_profiles(user_id=usr_abc)
                                 business_employees(business_id=biz_xyz, employee_id=usr_abc)
```

No DB-level foreign keys across services. Application-level integrity via REST calls.
