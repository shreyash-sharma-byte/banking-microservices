# 🖥️ Angular Screen Specifications — By Persona

> Every screen, every interaction, every API call.  
> Implementation-ready: each screen maps directly to a component.

---

## Global Layout

```
┌────────────────────────────────────────────────────────────┐
│  🏦 BankApp    [Dashboard] [Transfer] [History]  [👤 ▼]  │  ← Navbar
├────────────────────────────────────────────────────────────┤
│                                                            │
│                     <router-outlet>                        │  ← Screen content
│                                                            │
└────────────────────────────────────────────────────────────┘
```

**Navbar changes by role:**
- RETAIL: `Dashboard | Transfer | History`
- BUSINESS: `Dashboard | Transfer | Bulk | History | Employees`
- EMPLOYEE: `Dashboard | Accounts | Transactions | Daily Report`
- ADMIN: `Dashboard | Accounts | Users | Daily Report`
- AUDITOR: `Dashboard | Accounts | Transactions`

**Navbar right side (all roles):** `🔔 [Notifications] [👤 Priya Sharma ▼ Logout]`  
User name stored in localStorage after login/profile fetch.

**Shared components:**
- `<app-loading>` — spinner shown during API calls
- `<app-toast>` — success/error alerts (auto-dismiss 3s)
- Auth Guard — redirects to `/login` if no token
- HTTP Interceptor — attaches `Authorization: Bearer`, `X-Correlation-Id`, `Idempotency-Key` (auto-generated)

---

# ═══════════════════════════════════════════
# PERSONA 1: RETAIL — Priya
# ═══════════════════════════════════════════

## Screen R1: Login

```
┌──────────────────────────────────────┐
│         🏦 Banking App               │
│                                      │
│         ┌────────────────┐          │
│         │     LOGIN      │          │
│         └────────────────┘          │
│                                      │
│  Email:    [priya@test.com    ]     │
│  Password: [••••••••         ]     │
│                                      │
│  [          Login           ]       │
│                                      │
│  Don't have an account? Register    │  ← link to R2
│  Forgot password?                    │  ← link to R3
└──────────────────────────────────────┘
```

**Workflow:**
1. User types email + password → clicks Login
2. `POST /api/auth/login` → 200: `{token, refreshToken, userId}`
3. Store in localStorage → decode JWT to get role
4. If role=RETAIL → navigate to `/dashboard`
5. If 401 → show red error "Invalid credentials"
6. If 403 → show "Account locked. Contact support."

**API:** `POST /api/auth/login`

---

## Screen R2: Register

```
┌──────────────────────────────────────┐
│  Step 1 of 3: Create Account         │
│                                      │
│  Email:    [priya@email.com   ]     │
│  Password: [••••••••         ]     │
│  Confirm:  [••••••••         ]     │
│                                      │
│  Password must be: 8+ chars,         │
│  1 uppercase, 1 digit, 1 special     │
│                                      │
│  [          Next           ]        │
└──────────────────────────────────────┘
         ↓
┌──────────────────────────────────────┐
│  Step 2 of 3: Your Profile           │
│                                      │
│  Full Name:   [Priya Sharma   ]     │
│  Phone:       [9876543210     ]     │
│  Date of Birth: [1997-03-15   ]     │
│  PAN Number:  [ABCDP1234E     ]     │
│                                      │
│  [← Back]         [Next →]         │
└──────────────────────────────────────┘
         ↓
┌──────────────────────────────────────┐
│  Step 3 of 3: Open Account           │
│                                      │
│  Account Type: SAVINGS               │
│  Label:        [Primary Savings]    │
│  Initial Deposit: [5000        ]    │
│  (Minimum: ₹1,000)                   │
│                                      │
│  [← Back]    [Open Account ✓]      │
└──────────────────────────────────────┘
```

**Workflow:**
1. **Step 1:** `POST /api/auth/register` → 200 → store userId, token locally
2. **Step 2:** `POST /api/accounts/profile` with auth header → 200
3. **Step 3:** `POST /api/accounts` with `{accountType:SAVINGS, label, initialDeposit}` → 200
4. On success → navigate to `/dashboard`

**API calls:** `POST /api/auth/register`, `POST /api/accounts/profile`, `POST /api/accounts`

---

## Screen R3: Forgot Password

```
┌──────────────────────────────────────┐
│       Forgot Password                │
│                                      │
│  Enter your registered email:        │
│  [priya@email.com             ]     │
│                                      │
│  [        Send Reset Link     ]     │
│                                      │
│  ✅ If email exists, a reset link    │
│     has been sent. Check console.    │
│                                      │
│  ← Back to Login                     │
└──────────────────────────────────────┘
```

**Workflow:**
1. User types email → clicks Send
2. `POST /api/auth/forgot-password` → always 200 (don't leak existence)
3. Show success message. Mock: check console for link.
4. User copies reset token → goes to `/reset-password?token=xxx`

**API:** `POST /api/auth/forgot-password`

---

## Screen R3b: Reset Password (from email link)

```
┌──────────────────────────────────────┐
│       Reset Your Password            │
│                                      │
│  New Password: [••••••••     ]      │
│  Confirm:      [••••••••     ]      │
│                                      │
│  Password must be: 8+ chars,         │
│  1 uppercase, 1 digit, 1 special     │
│                                      │
│  [        Reset Password      ]     │
│                                      │
│  ✅ Password reset successful!       │
│     [Go to Login]                    │
└──────────────────────────────────────┘
```

**Workflow:**
1. User arrives from email link: `/reset-password?token=xxx`
2. Extract token from URL query param
3. User types new password twice → validation (match + strength)
4. `POST /api/auth/reset-password {token, newPassword}` → 200
5. Show success → "Go to Login" navigates to `/login`
6. If token expired → "Reset link expired. Request a new one."

**API:** `POST /api/auth/reset-password`

---

## Screen R10: Profile / Settings

```
┌──────────────────────────────────────────────┐
│  🏦 BankApp    [Dashboard] [Transfer] [History]  [👤 ▼] │
├──────────────────────────────────────────────┤
│                                              │
│  Welcome, Priya!                   ₹35,000   │  ← total balance
│                                              │
│  ┌─────────────────┐ ┌─────────────────┐    │
│  │ Primary Savings │ │ Emergency Fund  │    │  ← account cards
│  │ SAVINGS         │ │ SAVINGS         │    │
│  │ ₹5,000          │ │ ₹30,000         │    │
│  │ xxxx0001        │ │ xxxx0002        │    │
│  │ [View]          │ │ [View]          │    │
│  └─────────────────┘ └─────────────────┘    │
│                                              │
│  [+ Open New Account]                       │  ← link to R5
│                                              │
│  ─────────────────────────────────────────  │
│  Recent Transactions               [See All]│
│  ┌──────────────────────────────────────────┐
│  │ 🔴 -₹100  To Rahul Gupta     2 min ago  │
│  │ 🟢 +₹500  From ABC Exports   1 hr ago   │
│  │ 🟢 +₹5000 Initial deposit    2 hr ago   │
│  └──────────────────────────────────────────┘
└──────────────────────────────────────────────┘
```

**Workflow (on load):**
1. `GET /api/accounts/user/{userId}` → get all accounts → calculate total balance → render cards
2. `GET /api/transactions?accountId=X&page=0&size=5` → show recent 5 transactions
3. Click account card → navigate to `/accounts/{id}`
4. Click "See All" → navigate to `/transactions`
5. Click [+ Open New Account] → navigate to `/accounts/new`

**API calls:** `GET /api/accounts/user/{id}`, `GET /api/transactions?accountId=X&size=5`

---

## Screen R5: Open New Account

```
┌──────────────────────────────────────┐
│  ← Back to Dashboard                 │
│                                      │
│  Open New Account                    │
│                                      │
│  Account Type: SAVINGS               │
│  Label:        [______________]     │
│  Deposit:      [______________]     │
│  (Min: ₹1,000 for SAVINGS)          │
│                                      │
│  [        Open Account       ]      │
│                                      │
│  💡 You have 2 of 3 accounts.        │
└──────────────────────────────────────┘
```

**Workflow:**
1. Same as Register Step 3 but from logged-in state
2. `POST /api/accounts` → 200 → refresh dashboard
3. If 400 "Max 3 accounts" → disable button, show message
4. If 400 "Label already used" → highlight label field in red

**API:** `POST /api/accounts`

---

## Screen R6: Account Detail

```
┌──────────────────────────────────────────────┐
│  ← Dashboard                                 │
├──────────────────────────────────────────────┤
│  Primary Savings                SAVINGS      │
│  Account: 100200000001          ACTIVE       │
│                                              │
│         ┌─────────────────────┐              │
│         │    ₹5,000.00        │              │  ← big balance
│         │  Current Balance    │              │
│         └─────────────────────┘              │
│                                              │
│  [  Transfer Money  ]  [  Close Account  ]   │
│                                              │
│  ─────────────────────────────────────────  │
│  Transaction History for this account        │
│  ┌──────────────────────────────────────┐   │
│  │ 🔴 -₹100  To Rahul    2 min ago      │   │
│  │ 🟢 +₹5000 Initial      2 hr ago      │   │
│  └──────────────────────────────────────┘   │
└──────────────────────────────────────────────┘
```

**Workflow:**
1. `GET /api/accounts/{id}` → show account details
2. `GET /api/transactions?accountId={id}&size=10` → recent for this account
3. Click "Transfer Money" → navigate to `/transfer?from={accountId}`
4. Click "Close Account" → confirm dialog → `POST /api/accounts/{id}/close`
   - If balance > 0 → error "Transfer remaining balance first"
   - If last account → error "Cannot close last account"
   - If success → navigate to dashboard

**API calls:** `GET /api/accounts/{id}`, `GET /api/transactions?accountId={id}`, `POST /api/accounts/{id}/close`

---

## Screen R7: Transfer Money

```
┌──────────────────────────────────────────────┐
│  ← Dashboard        💸 Transfer Money        │
├──────────────────────────────────────────────┤
│                                              │
│  From Account: [Primary Savings (₹5,000) ▼] │  ← dropdown
│                                              │
│  To Account:   [100200030001          ]     │  ← type account number
│                or choose from beneficiaries  │
│                                              │
│  Amount:       [______________]             │
│  Remark:       [______________]             │
│                                              │
│  [           Send ₹___             ]        │
│                                              │
│  ┌─ Result (appears after submit) ─────────┐ │
│  │ ✅ Transfer successful!                  │ │
│  │    ₹100 sent to 100200030001            │ │
│  │    Txn ID: b6a62f77...                  │ │
│  │    New balance: ₹4,900                  │ │
│  │    [Done]  [Make Another]              │ │
│  └──────────────────────────────────────────┘ │
└──────────────────────────────────────────────┘
```

**Workflow:**
1. Load accounts: `GET /api/accounts/user/{userId}` → populate From dropdown
2. User fills form → clicks Send
3. Generate UUID for Idempotency-Key
4. `POST /api/payments/transfer` with headers:
   - `Authorization: Bearer <token>`
   - `Idempotency-Key: <uuid>`
   - `X-Correlation-Id: <uuid>`
5. 200 `{status:COMPLETED, paymentId}` → show green success with details
6. 400 `{error:"INSUFFICIENT_FUNDS"}` → show red error, don't clear form
7. User clicks "Done" → navigate to dashboard
8. User clicks "Make Another" → clear form, keep From dropdown

**Edge cases:**
- If Priya has only 1 account → From dropdown shows only that account
- If amount > balance → don't even send API, show inline "Insufficient balance"

**API:** `POST /api/payments/transfer`, `GET /api/accounts/user/{id}`

---

## Screen R8: Transaction History

```
┌──────────────────────────────────────────────┐
│  ← Dashboard          📊 Transaction History  │
├──────────────────────────────────────────────┤
│                                              │
│  Account: [All Accounts ▼]                   │  ← filter dropdown
│  Type:    [All ▼]  [DEBIT] [CREDIT]          │  ← filter chips
│  Amount:  Min [_____]  Max [_____]           │
│  Date:    From [2026-07-01] To [2026-08-05] │
│  [Apply Filters]                             │
│                                              │
│  ┌────────────────────────────────────────┐  │
│  │ Date       │ Type  │ Amount │ Remark   │  │
│  ├────────────────────────────────────────┤  │
│  │ 05-Aug 5PM │ DEBIT │ ₹100   │ To Rahul │  │
│  │ 05-Aug 3PM │ CREDIT│ ₹500   │ Salary   │  │
│  │ 05-Aug 2PM │ CREDIT│ ₹5000  │ Deposit  │  │
│  └────────────────────────────────────────┘  │
│                                              │
│  ← Previous    Page 1 of 3    Next →        │
└──────────────────────────────────────────────┘
```

**Workflow:**
1. Load accounts for dropdown: `GET /api/accounts/user/{id}`
2. Default: show all accounts, last 20 transactions
3. `GET /api/transactions?accountId=X&page=0&size=20`
4. Apply filters → re-fetch with query params
5. Pagination: Previous/Next buttons, show page numbers

**API:** `GET /api/transactions?accountId=X&type=X&minAmount=X&startDate=X&endDate=X&page=X&size=X`

---

## Screen R9: Notifications (Dropdown)

```
┌──────────────────────────┐
│ 🔔 Notifications    [×] │
├──────────────────────────┤
│                          │
│ 📱 ₹100 debited to       │
│    Rahul Gupta            │
│    2 min ago              │
│                          │
│ 📱 ₹500 credited from    │
│    ABC Exports            │
│    1 hour ago             │
│                          │
│ ──────────────────────── │
│     [See All]            │
└──────────────────────────┘
```

**Workflow:**
1. Click bell icon in navbar → dropdown appears
2. `GET /api/notifications?userId={id}&size=5` → show latest 5
3. "See All" → navigate to full notifications page

**API:** `GET /api/notifications?userId=X`

---

## Screen R10: Profile

```
┌──────────────────────────────────────┐
│  ← Dashboard     👤 My Profile       │
├──────────────────────────────────────┤
│                                      │
│  Full Name:  Priya Sharma            │
│  Email:      priya@test.com          │
│  Phone:      9876543210              │
│  DOB:        1997-03-15              │
│  PAN:        ABCDP1234E              │
│                                      │
│  [Edit]                              │  ← inline edit mode
│                                      │
│  ────────────────────────────────    │
│  Security                             │
│  [Change Password]                   │  ← opens change password modal
└──────────────────────────────────────┘
```

**Workflow:**
1. `GET /api/accounts/profile` → display profile fields
2. "Edit" → fields become editable (phone, address). PAN locked.
3. Save → `PUT /api/accounts/profile` (not built yet — Phase 2)
4. "Change Password" → modal with old password + new password → `POST /api/auth/reset-password` using current token (or dedicated endpoint)

**API:** `GET /api/accounts/profile`

---

## Screen R11: 404 Not Found

```
┌──────────────────────────────────────┐
│                                      │
│            🔍 404                    │
│        Page Not Found                │
│                                      │
│  The page you're looking for         │
│  doesn't exist.                      │
│                                      │
│        [Go to Dashboard]             │
│                                      │
└──────────────────────────────────────┘
```

**Workflow:** Catch-all route `**` → always redirect here.

---

# ═══════════════════════════════════════════
# PERSONA 2: BUSINESS — ABC Exports
# ═══════════════════════════════════════════

**Shares R1, R3, R3b (Login, Forgot Password, Reset Password). Register is different:**

## Screen B0: Business Registration

```
┌──────────────────────────────────────┐
│  Step 1 of 3: Company Account        │
│                                      │
│  Email:    [cfo@abcexports.com]     │
│  Password: [••••••••         ]     │
│  Confirm:  [••••••••         ]     │
│                                      │
│  [          Next           ]        │
└──────────────────────────────────────┘
         ↓
┌──────────────────────────────────────┐
│  Step 2 of 3: Company Details        │
│                                      │
│  Company Name: [ABC Exports Ltd.]   │
│  Contact Name: [Vikram Mehta  ]     │
│  Contact Phone:[9811122233    ]     │
│  GST Number:   [27AABCT1234E1Z5]   │
│  PAN Number:   [AABCT1234E    ]     │
│  Business Type:[PVT_LTD ▼]          │
│  Address:      [15 Industrial..]    │
│                                      │
│  [← Back]         [Next →]         │
└──────────────────────────────────────┘
         ↓
┌──────────────────────────────────────┐
│  Step 3 of 3: Open Account           │
│                                      │
│  Account Type: CURRENT               │
│  Label:        [Operations    ]     │
│  Initial Deposit: [100000     ]     │
│  (Minimum: ₹10,000)                  │
│                                      │
│  [← Back]    [Open Account ✓]      │
└──────────────────────────────────────┘
```

**Workflow:**
1. **Step 1:** `POST /api/auth/register {role:BUSINESS}` → 200
2. **Step 2:** `POST /api/accounts/business-profile` → 200
3. **Step 3:** `POST /api/accounts {accountType:CURRENT}` → 200 → navigate to `/dashboard`

**API calls:** `POST /api/auth/register`, `POST /api/accounts/business-profile`, `POST /api/accounts`

---

## Screen B1: Business Dashboard

```
┌──────────────────────────────────────────────────┐
│  🏦 BankApp  [Dashboard] [Transfer] [Bulk] [History] [Employees]  [👤 ▼] │
├──────────────────────────────────────────────────┤
│                                                  │
│  ABC Exports Ltd.                     ₹30,00,000 │
│                                                  │
│  ┌──────────┐ ┌──────────┐ ┌──────────┐         │
│  │Operations│ │ Payroll  │ │  Export  │         │
│  │ CURRENT  │ │ CURRENT  │ │ CURRENT  │         │
│  │ ₹25,00K  │ │ ₹5,00K   │ │ ₹0       │         │
│  │ [View]   │ │ [View]   │ │ [View]   │         │
│  └──────────┘ └──────────┘ └──────────┘         │
│                                                  │
│  [+ Open New Account]                            │
│                                                  │
│  ──────────────────────────────────────────────  │
│  👥 Employees: 3 active                          │
│  [Manage Employees →]                            │
│                                                  │
│  Recent Transactions                    [See All]│
│  ┌──────────────────────────────────────────────┐
│  │ 🔴 -₹50K  Salary to Priya      1 hr ago     │
│  │ 🔴 -₹45K  Salary to Rahul      1 hr ago     │
│  │ 🟢 +₹100K Initial deposit      2 hr ago     │
│  └──────────────────────────────────────────────┘
└──────────────────────────────────────────────────┘
```

**Workflow:** Same as R4 but shows employee count + bulk transfer link.

---

## Screen B2: Bulk Transfer

```
┌──────────────────────────────────────────────┐
│  ← Dashboard        📦 Bulk Transfer          │
├──────────────────────────────────────────────┤
│                                              │
│  From Account: [Payroll (₹5,00,000) ▼]      │
│  Category:     [SALARY ▼]                    │  ← SALARY, VENDOR, DIVIDEND, REFUND, GENERAL
│                                              │
│  ┌──────────────────────────────────────────┐│
│  │ #  │ Account Number │ Amount  │ Remark   ││
│  ├──────────────────────────────────────────┤│
│  │ 1  │ 100200000001   │ 50000   │ Priya    ││
│  │ 2  │ 100200030001   │ 45000   │ Rahul    ││
│  │ 3  │ [_________]    │ [____]  │ [______] ││  ← add row
│  └──────────────────────────────────────────┘│
│  [+ Add Row]                                 │
│                                              │
│  Total: ₹95,000 for 2 recipients             │
│  [       Send All (₹95,000)          ]       │
│                                              │
│  ┌─ Result ────────────────────────────────┐ │
│  │ ✅ 2 completed, 0 failed                 │ │
│  │ Batch ID: abc-123                        │ │
│  │ [View Details]                           │ │
│  └──────────────────────────────────────────┘ │
└──────────────────────────────────────────────┘
```

**Workflow:**
1. Load accounts for dropdown
2. User adds rows (manual entry or upload CSV)
3. Clicks "Send All" → `POST /api/payments/batch`
4. Response: `{batchId, results: [{toAccount, amount, status}]}`
5. Show summary: green ✅ for completed, red ❌ for failed with reason
6. "View Details" → expand to show each row result

**If employee list exists (Phase 2):** Show "Pay All Active Employees" button that auto-populates rows.

**API:** `POST /api/payments/batch`

---

## Screen B3: Employee Management

```
┌──────────────────────────────────────────────┐
│  ← Dashboard        👥 Employees              │
├──────────────────────────────────────────────┤
│                                              │
│  [+ Add Employee]                            │
│                                              │
│  ┌──────────────────────────────────────────┐│
│  │ Name    │ Code    │ Status  │ Actions    ││
│  ├──────────────────────────────────────────┤│
│  │ Priya   │ EMP-042 │ ACTIVE  │ [Remove]   ││
│  │ Rahul   │ EMP-043 │ ACTIVE  │ [Remove]   ││
│  └──────────────────────────────────────────┘│
│                                              │
│  ┌─ Add Employee (modal) ──────────────────┐ │
│  │ Employee User ID: [______________]       │ │
│  │ Employee Code:     [______________]     │ │
│  │                                         │ │
│  │ [Cancel]          [Add Employee]        │ │
│  └─────────────────────────────────────────┘ │
└──────────────────────────────────────────────┘
```

**Workflow:**
1. Load: `GET /api/business/{bizId}/employees` → render table
2. Add: `POST /api/business/{bizId}/employees` → `{added:1, skipped:0}`
3. Remove: `DELETE /api/business/{bizId}/employees/{empId}` → refresh list

**API calls:** `GET/POST/DELETE /api/business/{id}/employees`

---

# ═══════════════════════════════════════════
# PERSONA 3: EMPLOYEE — Rajesh
# ═══════════════════════════════════════════

## Screen E1: Employee Dashboard

```
┌──────────────────────────────────────────────────┐
│  🏦 BankApp  [Dashboard] [Accounts] [Transactions] [Daily Report]  [👤 ▼] │
├──────────────────────────────────────────────────┤
│                                                  │
│  Operations Dashboard               05 Aug 2026  │
│                                                  │
│  ┌──────────────┐ ┌──────────────┐              │
│  │ Today's Txns │ │ Total Volume │              │
│  │     12       │ │  ₹1,95,000   │              │
│  └──────────────┘ └──────────────┘              │
│                                                  │
│  Quick Actions:                                  │
│  [Search Account #]  [Freeze Account]           │
│                                                  │
│  ──────────────────────────────────────────────  │
│  Recent System Activity                          │
│  ┌──────────────────────────────────────────────┐│
│  │ 🔴 Account 1002...0001 frozen by Rajesh     ││
│  │ 🟢 Account 1002...0003 unfrozen             ││
│  └──────────────────────────────────────────────┘│
└──────────────────────────────────────────────────┘
```

**Workflow:**
1. `GET /api/transactions/daily-summary?date=today` → show cards
2. Search bar in navbar or quick action → navigate to account search

---

## Screen E2: Account Search + Actions

```
┌──────────────────────────────────────────────┐
│  ← Dashboard        🔍 Account Lookup         │
├──────────────────────────────────────────────┤
│                                              │
│  Account Number: [100200000001       ]      │
│  [Search]                                    │
│                                              │
│  ┌─ Search Result ─────────────────────────┐ │
│  │ Account: 100200000001                    │ │
│  │ Owner: Priya Sharma (RETAIL)            │ │
│  │ Type: SAVINGS | Label: Primary          │ │
│  │ Balance: ₹4,900 | Status: ACTIVE        │ │
│  │                                         │ │
│  │ [Freeze Account]   [View Transactions]  │ │
│  └─────────────────────────────────────────┘ │
└──────────────────────────────────────────────┘
```

**Workflow:**
1. User types 12-digit account number → Enter
2. `GET /api/accounts/search?accountNumber=XXX` → show result with owner info
3. "Freeze Account" → `PATCH /api/accounts/{id}/status {status:FROZEN}` → confirm dialog → success
4. "View Transactions" → navigate to transaction history for that account

**API:** `GET /api/accounts/search`, `PATCH /api/accounts/{id}/status`

---

## Screen E3: Daily Summary

```
┌──────────────────────────────────────────────┐
│  ← Dashboard        📊 Daily Report           │
├──────────────────────────────────────────────┤
│                                              │
│  Date: [2026-08-05           ] [Load]       │
│                                              │
│  ┌──────────────────────────────────────┐   │
│  │         Daily Summary                │   │
│  │         August 5, 2026               │   │
│  ├──────────────────────────────────────┤   │
│  │ Total Transactions: 12               │   │
│  │ Total Volume: ₹1,95,000              │   │
│  ├──────────────────────────────────────┤   │
│  │ DEBIT:  8 txns   ₹95,000            │   │
│  │ CREDIT: 4 txns   ₹1,00,000          │   │
│  └──────────────────────────────────────┘   │
└──────────────────────────────────────────────┘
```

**Workflow:**
1. Default: today's date → `GET /api/transactions/daily-summary?date=today`
2. Change date → re-fetch
3. Render breakdown by type

**API:** `GET /api/transactions/daily-summary?date=YYYY-MM-DD`

---

# ═══════════════════════════════════════════
# PERSONA 4: ADMIN
# ═══════════════════════════════════════════

## Screen A1: User Management

```
┌──────────────────────────────────────────────┐
│  ← Dashboard        👥 User Management        │
├──────────────────────────────────────────────┤
│                                              │
│  [+ Provision New User]                      │
│                                              │
│  ┌──────────────────────────────────────────┐│
│  │ Email           │ Role     │ Status │ Act ││
│  ├──────────────────────────────────────────┤│
│  │ priya@test.com  │ RETAIL   │ ACTIVE │ ⋮  ││
│  │ cfo@abcexport.. │ BUSINESS │ ACTIVE │ ⋮  ││
│  │ rajesh@bank.com │ EMPLOYEE │ ACTIVE │ ⋮  ││
│  └──────────────────────────────────────────┘│
│                                              │
│  ┌─ Provision User (modal) ────────────────┐ │
│  │ Email:    [______________]               │ │
│  │ Password: [______________]               │ │
│  │ Role:     [EMPLOYEE ▼]                   │ │
│  │ (EMPLOYEE, ADMIN, AUDITOR only)          │ │
│  │ [Cancel]           [Create User]         │ │
│  └─────────────────────────────────────────┘ │
│                                              │
│  ┌─ Row Actions (⋮ dropdown) ──────────────┐ │
│  │ Change Role → [RETAIL ▼] [Apply]         │ │
│  │ Deactivate → confirm dialog              │ │
│  └─────────────────────────────────────────┘ │
└──────────────────────────────────────────────┘
```

**Workflow:**
1. Load: `GET /api/admin/users` → render table
2. Provision: `POST /api/admin/users` → `{userId}` → refresh
3. Change role: `PATCH /api/admin/users/{id}/role` → user must re-login
4. Deactivate: `PATCH /api/admin/users/{id}/status {status:INACTIVE}` → user blocked

**API:** `GET/POST /api/admin/users`, `PATCH /api/admin/users/{id}/role`, `PATCH /api/admin/users/{id}/status`

---

# ═══════════════════════════════════════════
# PERSONA 5: AUDITOR (Read-Only Everything)
# ═══════════════════════════════════════════

Same screens as EMPLOYEE but:
- All tables are read-only
- No Freeze, No Unfreeze, No Provision, No Role Change
- Transaction history has expanded filters (date range, amount range)

---

## Screen Flow Map (Navigation)

```
                    ┌─────────┐
                    │  LOGIN  │
                    └────┬────┘
                         │
              ┌──────────┼──────────┐
              ▼          ▼          ▼
          RETAIL     BUSINESS    EMPLOYEE/ADMIN/AUDITOR
              │          │          │
              ▼          ▼          ▼
        ┌─────────┐ ┌─────────┐ ┌─────────────┐
        │DASHBOARD│ │DASHBOARD│ │OPS DASHBOARD│
        └────┬────┘ └────┬────┘ └──────┬──────┘
             │           │             │
   ┌────┬────┼───┬───┐   │   ┌────┬────┼────┬────┐
   ▼    ▼    ▼   ▼   ▼   ▼   ▼    ▼    ▼    ▼    ▼
  Txn  Acc  Open Acc Trans Bulk Emp Search Freeze Daily Users
  Hist Det  New  Det  fer  Trans Mgmt Acct  Acct  Report Mgmt
```

---

## API Endpoints Used by Frontend

| Endpoint | Screens Using It |
|----------|-----------------|
| `POST /api/auth/login` | R1 (Login) |
| `POST /api/auth/register` | R2 (Register Step 1) |
| `POST /api/auth/logout` | Navbar logout button |
| `POST /api/auth/refresh` | Auth interceptor (silent) |
| `POST /api/auth/forgot-password` | R3 (Forgot Password) |
| `POST /api/auth/reset-password` | R3 (Reset Password) |
| `POST /api/accounts/profile` | R2 (Register Step 2) |
| `GET /api/accounts/profile` | R2 (pre-fill on re-register) |
| `POST /api/accounts/business-profile` | Business Register Step 2 |
| `GET /api/accounts/business-profile` | Business profile view |
| `POST /api/accounts` | R2 Step 3, R5, B1 (+Open) |
| `GET /api/accounts/user/{id}` | R4, R7, B1 (load accounts) |
| `GET /api/accounts/{id}` | R6, B1 (account detail) |
| `POST /api/accounts/{id}/close` | R6 (close account) |
| `PATCH /api/accounts/{id}/status` | E2 (freeze/unfreeze) |
| `GET /api/accounts/search` | E2 (search by number) |
| `POST /api/payments/transfer` | R7 (transfer) |
| `POST /api/payments/batch` | B2 (bulk transfer) |
| `GET /api/transactions` | R4, R6, R8, B1 (history) |
| `GET /api/transactions/daily-summary` | E1, E3 |
| `GET /api/notifications` | R9 (notif dropdown) |
| `GET /api/business/{id}/employees` | B3 |
| `POST /api/business/{id}/employees` | B3 |
| `DELETE /api/business/{id}/employees/{eid}` | B3 |
| `GET /api/admin/users` | A1 |
| `POST /api/admin/users` | A1 |
| `PATCH /api/admin/users/{id}/role` | A1 |
| `PATCH /api/admin/users/{id}/status` | A1 |

---

## Summary

| Persona | Screens | API calls (unique) |
|---------|:---:|:---:|
| RETAIL | 11 | 13 |
| BUSINESS | 7 (4 shared + 3 unique) | 15 |
| EMPLOYEE | 3 | 5 |
| ADMIN | 2 (1 shared + 1 unique) | 4 extra |
| AUDITOR | 3 (all shared) | 0 extra |
| Shared (404, loading, toast) | 3 | 0 |
| **TOTAL** | **17 unique screens** | **24 endpoints used** |

---

## 🔴 Backend Changes Needed Before Frontend

| # | Change | Service | Effort |
|---|--------|---------|:---:|
| 1 | **CORS** — Allow `http://localhost:4200` in Gateway | api-gateway | 5 lines |
| 2 | **`GET /api/accounts`** (paginated list, EMPLOYEE/ADMIN) | account-service | 15 lines |
| 3 | **`GET /api/auth/me`** — returns `{userId, email, role}` from JWT | auth-service | 5 lines |

## 🟡 Missing Backend Endpoints (Phase 2, can defer)

| # | Endpoint | Why needed |
|---|----------|------------|
| 4 | `PUT /api/accounts/profile` | Edit phone/address on Profile screen |
| 5 | `PUT /api/accounts/business-profile` | Edit business contact info |

---

## Frontend Build Order

| Step | Feature | Time (est.) |
|:---:|---------|:---:|
| 1 | **Backend fixes** — CORS + 2 endpoints | 15 min |
| 2 | **Angular scaffold** — `ng new`, routing, shared components | 15 min |
| 3 | **Auth module** — login, register, interceptor, guard | 30 min |
| 4 | **Dashboard** — account cards, recent transactions | 20 min |
| 5 | **Transfer** — form, success/error | 15 min |
| 6 | **Transaction history** — table, filters, pagination | 15 min |
| 7 | **Business screens** — bulk transfer, employees | 20 min |
| 8 | **Employee/Admin screens** — search, freeze, daily report, users | 20 min |
| 9 | **Polish** — loading states, toast, error handling | 15 min |
