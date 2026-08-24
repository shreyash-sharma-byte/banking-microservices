# 🖥️ Angular Frontend — Planning Document

> Phase 2: Build a frontend on top of our Phase 1 backend.  
> **Principle:** YAGNI. One feature at a time. Verified against running backend.

---

## 1. Backend Changes Needed (Before Any Frontend Code)

### 🔴 Must Fix

| # | Change | Why |
|---|--------|-----|
| 1 | **CORS** — Gateway must allow `http://localhost:4200` | Angular dev server runs on 4200. Without CORS, browser blocks every request. |
| 2 | **Missing: `GET /api/accounts` (paginated list)** | EMPLOYEE/ADMIN need to see all accounts. Currently only `/{id}` and `/user/{userId}` exist. |

### 🟡 Should Add

| # | Change |
|---|--------|
| 3 | `GET /api/accounts/user/{userId}` currently only returns ACTIVE/FROZEN. Add CLOSED accounts too (or separate filter). |
| 4 | `GET /api/transactions/daily-summary?date=today` — already exists, verify it works with frontend-friendly response format. |

### 🟢 Nice to Have

| # | Change |
|---|--------|
| 5 | Responsive error format — all errors return `{"error": "message"}` (already done via @ControllerAdvice ✅) |
| 6 | `GET /api/auth/me` — returns current user info from JWT (avoids extra DB call on every page load) |

---

## 2. Auth Flow — How Angular Handles JWT

```
┌─────────────────────────────────────────────────────────┐
│                    Angular Auth Flow                    │
├─────────────────────────────────────────────────────────┤
│                                                         │
│  1. Login Page                                          │
│     User types email + password                         │
│     → POST /api/auth/login                              │
│     → Store {token, refreshToken, userId} in localStorage│
│                                                         │
│  2. HTTP Interceptor                                    │
│     Every outgoing request:                             │
│       → Attach Authorization: Bearer <token>            │
│       → Attach X-Correlation-Id (UUID)                  │
│                                                         │
│  3. Auth Guard                                          │
│     Route protection:                                   │
│       → Check token exists in localStorage              │
│       → Check token not expired (decode JWT.iat)        │
│       → If expired → call /api/auth/refresh             │
│       → If refresh fails → redirect to /login           │
│                                                         │
│  4. Role-Based Access                                   │
│     Decode JWT.role claim:                              │
│       → RETAIL → dashboard, transfer, history           │
│       → BUSINESS → dashboard, transfer, bulk, employees │
│       → EMPLOYEE → all accounts, freeze, daily summary  │
│       → ADMIN → everything + user management            │
│       → AUDITOR → read-only views                       │
│                                                         │
│  5. Logout                                              │
│     → POST /api/auth/logout                             │
│     → Clear localStorage                                │
│     → Redirect to /login                                │
└─────────────────────────────────────────────────────────┘
```

---

## 3. Angular Architecture

### Project Structure

```
banking-ui/
├── src/
│   ├── app/
│   │   ├── core/
│   │   │   ├── auth/
│   │   │   │   ├── auth.service.ts       ← login, register, logout, refresh
│   │   │   │   ├── auth.guard.ts         ← route protection
│   │   │   │   └── auth.interceptor.ts   ← attach JWT + correlation ID
│   │   │   └── models/
│   │   │       ├── user.model.ts
│   │   │       ├── account.model.ts
│   │   │       └── transaction.model.ts
│   │   │
│   │   ├── features/
│   │   │   ├── auth/
│   │   │   │   ├── login.component.ts
│   │   │   │   └── register.component.ts
│   │   │   ├── dashboard/
│   │   │   │   └── dashboard.component.ts    ← account summary + recent txns
│   │   │   ├── accounts/
│   │   │   │   ├── account-list.component.ts
│   │   │   │   └── account-detail.component.ts
│   │   │   ├── transfer/
│   │   │   │   ├── transfer.component.ts      ← single transfer
│   │   │   │   └── bulk-transfer.component.ts ← business only
│   │   │   ├── transactions/
│   │   │   │   └── transaction-history.component.ts
│   │   │   ├── employees/
│   │   │   │   └── employee-list.component.ts  ← business only
│   │   │   └── admin/
│   │   │       └── user-management.component.ts
│   │   │
│   │   ├── shared/
│   │   │   ├── navbar.component.ts
│   │   │   └── loading-spinner.component.ts
│   │   │
│   │   └── app.routes.ts                ← route definitions
│   │
│   └── environments/
│       └── environment.ts               ← API base URL: http://localhost:8080
```

### Routes

| Path | Component | Role | Description |
|------|-----------|:---:|-------------|
| `/login` | LoginComponent | Public | Email + password |
| `/register` | RegisterComponent | Public | New RETAIL or BUSINESS |
| `/dashboard` | DashboardComponent | All | Account cards, recent transactions |
| `/accounts` | AccountListComponent | All | List of user's accounts |
| `/accounts/:id` | AccountDetailComponent | All | Single account + balance |
| `/transfer` | TransferComponent | RETAIL, BUSINESS | Single transfer form |
| `/transfer/bulk` | BulkTransferComponent | BUSINESS | CSV-style bulk transfer |
| `/transactions` | TransactionHistoryComponent | All | Filtered, paginated ledger |
| `/employees` | EmployeeListComponent | BUSINESS | Manage employees |
| `/admin/users` | UserManagementComponent | ADMIN | Provision, role change, deactivate |
| `/admin/daily-summary` | DailySummaryComponent | EMPLOYEE, ADMIN | Transaction stats for date |

---

## 4. Page Designs (Minimal — YAGNI)

### Dashboard

```
┌────────────────────────────────────────────┐
│  🏦 Banking App          [Priya ▼] [🔔]   │
├────────────────────────────────────────────┤
│                                            │
│  💰 Total Balance: ₹35,000                 │
│                                            │
│  ┌──────────┐  ┌──────────┐               │
│  │ Primary  │  │Emergency │               │
│  │ Savings  │  │  Fund    │               │
│  │ ₹5,000   │  │ ₹30,000  │               │
│  │ ...1001  │  │ ...1002  │               │
│  └──────────┘  └──────────┘               │
│                                            │
│  📊 Recent Transactions                    │
│  ┌──────────────────────────────────────┐ │
│  │ -₹100  Transfer to Rahul   2 min ago │ │
│  │ +₹500  Salary from ABC      1 hr ago │ │
│  │ +₹5000 Initial deposit     2 hr ago  │ │
│  └──────────────────────────────────────┘ │
└────────────────────────────────────────────┘
```

### Transfer

```
┌────────────────────────────────────────────┐
│  ← Back to Dashboard                       │
├────────────────────────────────────────────┤
│  💸 Transfer Money                         │
│                                            │
│  From Account: [Primary Savings ▼]         │
│  To Account:   [________________]          │
│  Amount:       [________________]          │
│  Remark:       [________________]          │
│                                            │
│  [           Send Money           ]        │
│                                            │
│  ✅ Transfer successful!                   │
│  Txn ID: b6a62f77-eec5-...                 │
└────────────────────────────────────────────┘
```

---

## 5. API → Component Mapping

| API Endpoint | Used By Component |
|-------------|------------------|
| `POST /api/auth/login` | LoginComponent |
| `POST /api/auth/register` | RegisterComponent |
| `POST /api/auth/logout` | Navbar (logout button) |
| `POST /api/auth/refresh` | AuthInterceptor (silent) |
| `GET /api/accounts/user/{id}` | DashboardComponent, AccountListComponent |
| `GET /api/accounts/{id}` | AccountDetailComponent |
| `POST /api/accounts` | RegisterComponent (onboarding step 3) |
| `POST /api/accounts/profile` | RegisterComponent (onboarding step 2) |
| `POST /api/payments/transfer` | TransferComponent |
| `POST /api/payments/batch` | BulkTransferComponent |
| `GET /api/transactions?accountId=X` | DashboardComponent, TransactionHistoryComponent |
| `GET /api/transactions/daily-summary` | DailySummaryComponent |
| `GET /api/notifications?userId=X` | Navbar (bell icon) |
| `PATCH /api/accounts/{id}/status` | AdminComponent (freeze/unfreeze) |
| `POST /api/business/{id}/employees` | EmployeeListComponent |
| `GET /api/business/{id}/employees` | EmployeeListComponent |

---

## 6. Build Order (One Feature at a Time)

| Step | Feature | Backend Changes | Frontend Files |
|:---:|---------|:---:|---|
| 1 | **CORS fix** | Gateway: allow localhost:4200 | — |
| 2 | **Missing endpoints** | `GET /api/accounts` paginated, `GET /api/auth/me` | — |
| 3 | **Login/Register** | None | auth.service, login, register components |
| 4 | **Auth interceptor + guard** | None | interceptor, guard, route config |
| 5 | **Dashboard** | None | dashboard component, account cards |
| 6 | **Transfer** | None | transfer form, success/error display |
| 7 | **Transaction history** | None | paginated table, filters |
| 8 | **Employee management** | None | list, add, remove |
| 9 | **Admin panel** | None | user CRUD, daily summary |
| 10 | **Polish** | None | loading states, error handling, responsive |

---

## 7. State Management (YAGNI)

**No NgRx. No Redux.** For MVP, each component manages its own state via service calls. The only shared state is the auth token in localStorage.

```typescript
// auth.service.ts — the only service with state
@Injectable({ providedIn: 'root' })
class AuthService {
  private token: string | null = localStorage.getItem('token');
  
  getToken(): string | null { return this.token; }
  getRole(): string | null { return this.decodeJwt()?.role; }
  getUserId(): string | null { return this.decodeJwt()?.sub; }
}
```

---

## Next Steps

1. **Fix backend:** CORS + 2 missing endpoints (~15 min)
2. **Scaffold Angular:** `ng new banking-ui` with routing
3. **Build auth:** Login → Register → Guard → Interceptor
4. **Build dashboard:** API call → display accounts + transactions
5. **Build transfer:** Form → API call → success message

Want me to start with the backend CORS fix and missing endpoints?
