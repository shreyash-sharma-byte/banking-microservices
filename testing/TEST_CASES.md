# 🧪 Test Cases — Banking Microservices

> Every flow from `docs/FLOWS.md` mapped to testable cases.  
> Format: **Given → When → Then**. Preconditions, Steps, Expected Result, Status.

---

## How to Use

1. Start Docker: `sudo docker compose up -d`
2. Start services in order: Eureka (8761) → Config (8888) → Auth (8081) → Account (8082) → Payment (8083) → Transaction (8084) → Notification (8085) → Gateway (8080)
3. Run test cases top-down — each builds on the previous
4. Mark ✅ or ❌ in the Status column. Add notes on failure.

---

## Test Data

| Variable | Value |
|----------|-------|
| Priya email | `priya@test.com` |
| Priya password | `Test@123` |
| ABC Exports email | `cfo@abcexports.com` |
| ABC Exports password | `Biz@12345` |
| Rajesh email | `rajesh@bank.com` |
| Rajesh password | `Admin@123` |
| Rahul email | `rahul@test.com` |
| Rahul password | `Test@123` |

---

# ═══════════════════════════════════════════
# RETAIL — Priya (19 test cases)
# ═══════════════════════════════════════════

## TC-R01: Register Retail User
| Field | Value |
|--------|-------|
| **Flow** | F-R01 |
| **Endpoint** | `POST /api/auth/register` |
| **Preconditions** | Auth Service running. DB empty for this email. |
| **Steps** | 1. Send `{"email":"priya@test.com","password":"Test@123"}` |
| **Expected** | 200. Response has `userId` (UUID), `token` (JWT), `refreshToken`, `expiresIn: 3600`. |
| **Status** | ⬜ |

## TC-R02: Register Duplicate Email
| Field | Value |
|--------|-------|
| **Flow** | F-R01 (edge) |
| **Endpoint** | `POST /api/auth/register` |
| **Preconditions** | Priya already registered. |
| **Steps** | 1. Send same register payload as TC-R01. |
| **Expected** | 400. `{"error":"Email already registered"}`. |
| **Status** | ⬜ |

## TC-R03: Register Weak Password
| Field | Value |
|--------|-------|
| **Flow** | F-R01 (edge) |
| **Endpoint** | `POST /api/auth/register` |
| **Preconditions** | None. |
| **Steps** | 1. Send `{"email":"weak@test.com","password":"123"}` |
| **Expected** | 400. Error about password strength. |
| **Status** | ⬜ |

## TC-R04: Login
| Field | Value |
|--------|-------|
| **Flow** | F-R04 |
| **Endpoint** | `POST /api/auth/login` |
| **Preconditions** | TC-R01 passed. |
| **Steps** | 1. Send `{"email":"priya@test.com","password":"Test@123"}` |
| **Expected** | 200. `token`, `refreshToken`, `userId`, `expiresIn`. Save token as `$PRIYA_TOKEN`. |
| **Status** | ⬜ |

## TC-R05: Login Wrong Password
| Field | Value |
|--------|-------|
| **Flow** | F-R04 (edge) |
| **Endpoint** | `POST /api/auth/login` |
| **Preconditions** | Priya exists. |
| **Steps** | 1. Send `{"email":"priya@test.com","password":"WrongPass"}` |
| **Expected** | 401. `{"error":"Invalid credentials"}`. |
| **Status** | ⬜ |

## TC-R06: Login Non-Existent User
| Field | Value |
|--------|-------|
| **Flow** | F-R04 (edge) |
| **Endpoint** | `POST /api/auth/login` |
| **Preconditions** | None. |
| **Steps** | 1. Send `{"email":"nobody@test.com","password":"Test@123"}` |
| **Expected** | 401. `{"error":"Invalid credentials"}`. Does NOT reveal email doesn't exist. |
| **Status** | ⬜ |

## TC-R07: Validate JWT (Internal)
| Field | Value |
|--------|-------|
| **Flow** | Token Validation |
| **Endpoint** | `GET /api/auth/validate` |
| **Preconditions** | TC-R04 passed. |
| **Steps** | 1. Send with `Authorization: Bearer $PRIYA_TOKEN` |
| **Expected** | 200. `{"sub":"...","role":"RETAIL","valid":true}`. |
| **Status** | ⬜ |

## TC-R08: Validate Expired/Invalid JWT
| Field | Value |
|--------|-------|
| **Flow** | Token Validation (edge) |
| **Endpoint** | `GET /api/auth/validate` |
| **Preconditions** | None. |
| **Steps** | 1. Send with `Authorization: Bearer invalidtoken123` |
| **Expected** | 401. |
| **Status** | ⬜ |

## TC-R09: Complete Retail Profile
| Field | Value |
|--------|-------|
| **Flow** | F-R02 |
| **Endpoint** | `POST /api/accounts/profile` |
| **Preconditions** | TC-R04 passed. Account Service running. |
| **Steps** | 1. Header: `Authorization: Bearer $PRIYA_TOKEN`. Body: `{"fullName":"Priya Sharma","phone":"9876543210","dateOfBirth":"1997-03-15","panNumber":"ABCDP1234E"}` |
| **Expected** | 200. Returns profile with `userId`, `fullName`, etc. |
| **Status** | ⬜ |

## TC-R10: Get Retail Profile
| Field | Value |
|--------|-------|
| **Flow** | F-R02 (read) |
| **Endpoint** | `GET /api/accounts/profile` |
| **Preconditions** | TC-R09 passed. |
| **Steps** | 1. Header: `Authorization: Bearer $PRIYA_TOKEN`. |
| **Expected** | 200. Returns Priya's profile. |
| **Status** | ⬜ |

## TC-R11: Open Savings Account
| Field | Value |
|--------|-------|
| **Flow** | F-R03 |
| **Endpoint** | `POST /api/accounts` |
| **Preconditions** | TC-R09 passed. |
| **Steps** | 1. Header: `Authorization: Bearer $PRIYA_TOKEN`. Body: `{"accountType":"SAVINGS","label":"Primary Savings","initialDeposit":5000}` |
| **Expected** | 200. `accountId`, `accountNumber` (12-digit starting with 1002), `balance:5000`. Save `$PRIYA_ACC_ID`. |
| **Status** | ⬜ |

## TC-R12: Open Account — Insufficient Deposit
| Field | Value |
|--------|-------|
| **Flow** | F-R03 (edge) |
| **Endpoint** | `POST /api/accounts` |
| **Preconditions** | TC-R09 passed. |
| **Steps** | 1. `{"accountType":"SAVINGS","label":"Test","initialDeposit":500}` |
| **Expected** | 400. Error about minimum deposit (₹1000). |
| **Status** | ⬜ |

## TC-R13: Open Account — Duplicate Label
| Field | Value |
|--------|-------|
| **Flow** | F-R03 (edge) |
| **Endpoint** | `POST /api/accounts` |
| **Preconditions** | TC-R11 passed. |
| **Steps** | 1. `{"accountType":"SAVINGS","label":"Primary Savings","initialDeposit":2000}` |
| **Expected** | 400. "Label already used". |
| **Status** | ⬜ |

## TC-R14: Check Balance
| Field | Value |
|--------|-------|
| **Flow** | F-R05 |
| **Endpoint** | `GET /api/accounts/{accountId}` |
| **Preconditions** | TC-R11 passed. |
| **Steps** | 1. Header: `Authorization: Bearer $PRIYA_TOKEN`. GET with `$PRIYA_ACC_ID`. |
| **Expected** | 200. `balance:5000`, `accountType:"SAVINGS"`, `status:"ACTIVE"`. |
| **Status** | ⬜ |

## TC-R15: Check Balance — Not Your Account
| Field | Value |
|--------|-------|
| **Flow** | F-R05 (edge) |
| **Endpoint** | `GET /api/accounts/{accountId}` |
| **Preconditions** | Two different users exist. |
| **Steps** | 1. Priya's token tries to view Rahul's account. |
| **Expected** | 403. |
| **Status** | ⬜ |

## TC-R16: List My Accounts
| Field | Value |
|--------|-------|
| **Flow** | F-R06 |
| **Endpoint** | `GET /api/accounts/user/{userId}` |
| **Preconditions** | TC-R11 passed. |
| **Steps** | 1. GET with Priya's userId from JWT. |
| **Expected** | 200. List with 1 account (Primary Savings). |
| **Status** | ⬜ |

## TC-R17: Open Second Account (up to 3)
| Field | Value |
|--------|-------|
| **Flow** | F-R03 (multi-account) |
| **Endpoint** | `POST /api/accounts` |
| **Preconditions** | TC-R11 passed. |
| **Steps** | 1. `{"accountType":"SAVINGS","label":"Emergency Fund","initialDeposit":30000}` |
| **Expected** | 200. New account created. `$PRIYA_ACC2_ID`. |
| **Status** | ⬜ |

## TC-R18: Open 4th Account — Blocked
| Field | Value |
|--------|-------|
| **Flow** | F-R03 (edge) |
| **Endpoint** | `POST /api/accounts` |
| **Preconditions** | Priya has 3 accounts. |
| **Steps** | 1. Try to open a 4th SAVINGS account. |
| **Expected** | 400. "Max 3 accounts". |
| **Status** | ⬜ |

## TC-R19: Forgot Password
| Field | Value |
|--------|-------|
| **Flow** | F-R15 |
| **Endpoint** | `POST /api/auth/forgot-password` |
| **Preconditions** | TC-R01 passed. |
| **Steps** | 1. `{"email":"priya@test.com"}` |
| **Expected** | 200. `{"message":"If the email exists, a reset link has been sent"}`. Check console for mock link. |
| **Status** | ⬜ |

## TC-R20: Forgot Password — Non-existent Email
| Field | Value |
|--------|-------|
| **Flow** | F-R15 (edge) |
| **Endpoint** | `POST /api/auth/forgot-password` |
| **Preconditions** | None. |
| **Steps** | 1. `{"email":"nobody@test.com"}` |
| **Expected** | 200. Same message. Does NOT reveal email doesn't exist. |
| **Status** | ⬜ |

## TC-R21: Logout
| Field | Value |
|--------|-------|
| **Flow** | F-R17 |
| **Endpoint** | `POST /api/auth/logout` |
| **Preconditions** | TC-R04 passed. |
| **Steps** | 1. Header: `Authorization: Bearer $PRIYA_TOKEN`. |
| **Expected** | 200. `{"message":"Logged out"}`. |
| **Status** | ⬜ |

## TC-R22: Use Token After Logout
| Field | Value |
|--------|-------|
| **Flow** | F-R17 (edge) |
| **Endpoint** | `GET /api/accounts/profile` |
| **Preconditions** | TC-R21 passed (logged out). |
| **Steps** | 1. Use same `$PRIYA_TOKEN` for any authenticated endpoint. |
| **Expected** | 401. Token is blacklisted. |
| **Status** | ⬜ |

## TC-R23: Refresh Token
| Field | Value |
|--------|-------|
| **Flow** | Refresh Token |
| **Endpoint** | `POST /api/auth/refresh` |
| **Preconditions** | TC-R04 passed (have refreshToken). |
| **Steps** | 1. `{"refreshToken":"$PRIYA_REFRESH_TOKEN"}` |
| **Expected** | 200. New `token` + new `refreshToken` (rotated). Old refresh token revoked. |
| **Status** | ⬜ |

## TC-R24: Login After Relogin (Fresh Token)
| Field | Value |
|--------|-------|
| **Flow** | F-R04 |
| **Endpoint** | `POST /api/auth/login` |
| **Preconditions** | Priya logged out (TC-R21). |
| **Steps** | 1. Login again. |
| **Expected** | 200. Fresh token works. Old token still blacklisted. |
| **Status** | ⬜ |

---

# ═══════════════════════════════════════════
# BUSINESS — ABC Exports (14 test cases)
# ═══════════════════════════════════════════

## TC-B01: Register Business
| Field | Value |
|--------|-------|
| **Flow** | F-B01 |
| **Endpoint** | `POST /api/auth/register` |
| **Preconditions** | Auth running. |
| **Steps** | 1. `{"email":"cfo@abcexports.com","password":"Biz@12345","role":"BUSINESS"}` |
| **Expected** | 200. JWT with `role:BUSINESS`. Save as `$BIZ_TOKEN`. |
| **Status** | ⬜ |

## TC-B02: Create Business Profile
| Field | Value |
|--------|-------|
| **Flow** | F-B02 |
| **Endpoint** | `POST /api/accounts/business-profile` |
| **Preconditions** | TC-B01 passed. Account Service running. |
| **Steps** | 1. Header: `Authorization: Bearer $BIZ_TOKEN`. Body: `{"companyName":"ABC Exports Ltd.","contactName":"Vikram Mehta","contactPhone":"9811122233","gstNumber":"27AABCT1234E1Z5","panNumber":"AABCT1234E","businessType":"PVT_LTD","registeredAddress":"15 Industrial Area, Mumbai"}` |
| **Expected** | 200. Business profile returned. |
| **Status** | ⬜ |

## TC-B03: RETAIL Tries Business Profile
| Field | Value |
|--------|-------|
| **Flow** | F-B02 (edge) |
| **Endpoint** | `POST /api/accounts/business-profile` |
| **Preconditions** | TC-R04 passed. |
| **Steps** | 1. Use Priya's RETAIL token to call business-profile endpoint. |
| **Expected** | 403. "BUSINESS role required". |
| **Status** | ⬜ |

## TC-B04: Open Current Account
| Field | Value |
|--------|-------|
| **Flow** | F-B03 |
| **Endpoint** | `POST /api/accounts` |
| **Preconditions** | TC-B02 passed. |
| **Steps** | 1. Header: `$BIZ_TOKEN`. Body: `{"accountType":"CURRENT","label":"Operations","initialDeposit":100000}` |
| **Expected** | 200. Account created. Save `$BIZ_ACC_ID`. |
| **Status** | ⬜ |

## TC-B05: Open Current Account — Insufficient Deposit
| Field | Value |
|--------|-------|
| **Flow** | F-B03 (edge) |
| **Endpoint** | `POST /api/accounts` |
| **Preconditions** | TC-B02 passed. |
| **Steps** | 1. `{"accountType":"CURRENT","label":"Test","initialDeposit":5000}` |
| **Expected** | 400. "Min deposit: ₹10000". |
| **Status** | ⬜ |

## TC-B06: Open Additional Current Account
| Field | Value |
|--------|-------|
| **Flow** | F-B03 (unlimited) |
| **Endpoint** | `POST /api/accounts` |
| **Preconditions** | TC-B04 passed. |
| **Steps** | 1. `{"accountType":"CURRENT","label":"Payroll","initialDeposit":500000}` |
| **Expected** | 200. Second account created. BUSINESS has no max limit. |
| **Status** | ⬜ |

## TC-B07: Register Second Retail User (Rahul)
| Field | Value |
|--------|-------|
| **Flow** | F-R01 |
| **Endpoint** | `POST /api/auth/register` |
| **Preconditions** | Auth running. |
| **Steps** | 1. `{"email":"rahul@test.com","password":"Test@123"}` |
| **Expected** | 200. Save `$RAHUL_TOKEN`, `$RAHUL_USER_ID`. |
| **Status** | ⬜ |

## TC-B08: Rahul — Complete Profile
| Field | Value |
|--------|-------|
| **Flow** | F-R02 |
| **Endpoint** | `POST /api/accounts/profile` |
| **Preconditions** | TC-B07 passed. |
| **Steps** | 1. `{"fullName":"Rahul Gupta","phone":"9811122234","dateOfBirth":"1995-06-20","panNumber":"XYZAB5678C"}` |
| **Expected** | 200. |
| **Status** | ⬜ |

## TC-B09: Rahul — Open Savings Account
| Field | Value |
|--------|-------|
| **Flow** | F-R03 |
| **Endpoint** | `POST /api/accounts` |
| **Preconditions** | TC-B08 passed. |
| **Steps** | 1. `{"accountType":"SAVINGS","label":"Primary","initialDeposit":10000}` |
| **Expected** | 200. Save `$RAHUL_ACC_ID`. |
| **Status** | ⬜ |

## TC-B10: Add Employee (Rahul to ABC Exports)
| Field | Value |
|--------|-------|
| **Flow** | F-B06 |
| **Endpoint** | `POST /api/business/{bizId}/employees` |
| **Preconditions** | TC-B01, TC-B07 passed. |
| **Steps** | 1. Header: `$BIZ_TOKEN`. Body: `{"employees":[{"employeeUserId":"$RAHUL_USER_ID","employeeCode":"EMP-043"}]}` |
| **Expected** | 200. `{"added":1,"skipped":0}`. |
| **Status** | ⬜ |

## TC-B11: Add Employee — Duplicate
| Field | Value |
|--------|-------|
| **Flow** | F-B06 (edge) |
| **Endpoint** | `POST /api/business/{bizId}/employees` |
| **Preconditions** | TC-B10 passed. |
| **Steps** | 1. Same payload as TC-B10. |
| **Expected** | 200. `{"added":0,"skipped":1}` (ON CONFLICT skips). |
| **Status** | ⬜ |

## TC-B12: List Employees
| Field | Value |
|--------|-------|
| **Flow** | F-B08 |
| **Endpoint** | `GET /api/business/{bizId}/employees` |
| **Preconditions** | TC-B10 passed. |
| **Steps** | 1. Header: `$BIZ_TOKEN`. |
| **Expected** | 200. List with Rahul (EMP-043, ACTIVE). |
| **Status** | ⬜ |

## TC-B13: Remove Employee
| Field | Value |
|--------|-------|
| **Flow** | F-B07 |
| **Endpoint** | `DELETE /api/business/{bizId}/employees/{empUserId}` |
| **Preconditions** | TC-B10 passed. |
| **Steps** | 1. Header: `$BIZ_TOKEN`. Remove Rahul. |
| **Expected** | 200. `{"success":true,"convertedAccounts":0}` (Rahul has no SALARY account). |
| **Status** | ⬜ |

## TC-B14: Non-Business Tries Add Employees
| Field | Value |
|--------|-------|
| **Flow** | F-B06 (edge) |
| **Endpoint** | `POST /api/business/{bizId}/employees` |
| **Preconditions** | TC-R04 passed. |
| **Steps** | 1. Use Priya's RETAIL token. |
| **Expected** | 403. |
| **Status** | ⬜ |

---

# ═══════════════════════════════════════════
# SALARY ACCOUNT — End-to-End (5 test cases)
# ═══════════════════════════════════════════

## TC-S01: Re-add Rahul as Employee
| Field | Value |
|--------|-------|
| **Flow** | F-B06 |
| **Endpoint** | `POST /api/business/{bizId}/employees` |
| **Preconditions** | TC-B13 passed (was removed). |
| **Steps** | 1. Re-add Rahul to ABC Exports. |
| **Expected** | 200. |
| **Status** | ⬜ |

## TC-S02: Rahul Opens Salary Account
| Field | Value |
|--------|-------|
| **Flow** | F-R18 |
| **Endpoint** | `POST /api/accounts` |
| **Preconditions** | TC-S01 passed (Rahul is employee). |
| **Steps** | 1. Header: `$RAHUL_TOKEN`. Body: `{"accountType":"SALARY","label":"ABC Exports Salary","employerBusinessId":"$BIZ_USER_ID","initialDeposit":0}` |
| **Expected** | 200. Salary account created with 0 balance. |
| **Status** | ⬜ |

## TC-S03: Open Salary — Not an Employee
| Field | Value |
|--------|-------|
| **Flow** | F-R18 (edge) |
| **Endpoint** | `POST /api/accounts` |
| **Preconditions** | Priya is NOT an employee of ABC Exports. |
| **Steps** | 1. Priya tries to open SALARY account with employerBusinessId=ABC Exports. |
| **Expected** | 400. "Not an employee of this business". |
| **Status** | ⬜ |

## TC-S04: Open Second Salary Account — Blocked
| Field | Value |
|--------|-------|
| **Flow** | F-R18 (edge) |
| **Endpoint** | `POST /api/accounts` |
| **Preconditions** | TC-S02 passed. |
| **Steps** | 1. Rahul tries another SALARY account. |
| **Expected** | 400. "Already have a salary account". |
| **Status** | ⬜ |

## TC-S05: Remove Employee → SALARY Converts to SAVINGS
| Field | Value |
|--------|-------|
| **Flow** | F-B07 |
| **Endpoint** | `DELETE /api/business/{bizId}/employees/{empUserId}` |
| **Preconditions** | TC-S02 passed. |
| **Steps** | 1. Remove Rahul from ABC Exports. 2. Check Rahul's salary account. |
| **Expected** | `convertedAccounts:1`. Account type is now `SAVINGS`. |
| **Status** | ⬜ |

---

# ═══════════════════════════════════════════
# TRANSFERS — Money Movement (8 test cases)
# ═══════════════════════════════════════════

### Pre-setup: Ensure all services running (Account, Payment, Transaction, Notification, Kafka)

## TC-T01: Single Transfer (Priya → Rahul)
| Field | Value |
|--------|-------|
| **Flow** | F-R07 |
| **Endpoint** | `POST /api/payments/transfer` |
| **Preconditions** | TC-R11 (Priya has ₹5000), TC-B09 (Rahul has ₹10000). Payment, Account, Transaction, Notification all running. Kafka running. |
| **Steps** | 1. Header: `Authorization: Bearer $PRIYA_TOKEN`, `Idempotency-Key: <UUID>`. Body: `{"fromAccount":"$PRIYA_ACC_ID","toAccount":"$RAHUL_ACC_ID","amount":1000,"remark":"Test transfer"}` |
| **Expected** | 200. `status:"COMPLETED"`. Payment recorded. |
| **Status** | ⬜ |

## TC-T02: Verify Balances After Transfer
| Field | Value |
|--------|-------|
| **Flow** | F-R05 |
| **Endpoint** | `GET /api/accounts/{id}` |
| **Preconditions** | TC-T01 passed. |
| **Steps** | 1. Check Priya's balance. 2. Check Rahul's balance. |
| **Expected** | Priya: ₹4000. Rahul: ₹11000. |
| **Status** | ⬜ |

## TC-T03: Transfer — Insufficient Funds
| Field | Value |
|--------|-------|
| **Flow** | F-R07 (edge) |
| **Endpoint** | `POST /api/payments/transfer` |
| **Preconditions** | Priya has ₹4000. |
| **Steps** | 1. Try transfer ₹5000 from Priya to Rahul. |
| **Expected** | 400. `INSUFFICIENT_FUNDS`. Balances unchanged. |
| **Status** | ⬜ |

## TC-T04: Transfer — Idempotency
| Field | Value |
|--------|-------|
| **Flow** | F-R07 (edge) |
| **Endpoint** | `POST /api/payments/transfer` |
| **Preconditions** | TC-T01 passed. |
| **Steps** | 1. Use SAME Idempotency-Key as TC-T01, same body. |
| **Expected** | 200. Returns ORIGINAL paymentId. Balance NOT deducted again. |
| **Status** | ⬜ |

## TC-T05: Bulk Transfer (ABC Exports Payroll)
| Field | Value |
|--------|-------|
| **Flow** | F-B05 |
| **Endpoint** | `POST /api/payments/batch` |
| **Preconditions** | TC-B04 (ABC has account). Priya and Rahul have accounts. |
| **Steps** | 1. Header: `$BIZ_TOKEN`. Body: `{"fromAccount":"$BIZ_ACC_ID","category":"SALARY","transfers":[{"toAccount":"$PRIYA_ACC_ID","amount":50000,"remark":"Priya July Salary"},{"toAccount":"$RAHUL_ACC_ID","amount":45000,"remark":"Rahul July Salary"}]}` |
| **Expected** | 200. Both transfers COMPLETED. `successful:2`. |
| **Status** | ⬜ |

## TC-T06: Verify Transaction History (Priya)
| Field | Value |
|--------|-------|
| **Flow** | F-R12 |
| **Endpoint** | `GET /api/transactions?accountId=$PRIYA_ACC_ID&page=0&size=10` |
| **Preconditions** | TC-T01 and TC-T05 passed. Transaction Service running. |
| **Steps** | 1. Query Priya's transactions. |
| **Expected** | 200. Shows DEBIT (₹1000 to Rahul) and CREDIT (₹50000 from ABC). At least 2 entries. |
| **Status** | ⬜ |

## TC-T07: Filtered Transactions (Debit only, above ₹500)
| Field | Value |
|--------|-------|
| **Flow** | F-R13 |
| **Endpoint** | `GET /api/transactions?accountId=$PRIYA_ACC_ID&type=DEBIT&minAmount=500` |
| **Preconditions** | TC-T01 passed. |
| **Steps** | 1. Query with filters. |
| **Expected** | 200. Only DEBIT entries ≥ 500. Should include ₹1000 transfer. |
| **Status** | ⬜ |

## TC-T08: View Payment Status
| Field | Value |
|--------|-------|
| **Flow** | Payment History |
| **Endpoint** | `GET /api/payments?accountId=$PRIYA_ACC_ID` |
| **Preconditions** | TC-T01 passed. |
| **Steps** | 1. Query payments for Priya's account. |
| **Expected** | 200. Shows completed payments. |
| **Status** | ⬜ |

---

# ═══════════════════════════════════════════
# NOTIFICATIONS (3 test cases)
# ═══════════════════════════════════════════

## TC-N01: Transfer Generates Notifications
| Field | Value |
|--------|-------|
| **Flow** | F-R08 |
| **System** | Outbox poller (1s) → Kafka → Notification consumer |
| **Preconditions** | Notification Service + Kafka running. |
| **Steps** | 1. Make a transfer (TC-T01). 2. Wait 2 seconds. 3. Check Notification Service logs. |
| **Expected** | Console shows `📱 SMS: ₹... debited` and `📱 SMS: ₹... credited`. Two notification rows in DB. |
| **Status** | ⬜ |

## TC-N02: View Notifications (Priya)
| Field | Value |
|--------|-------|
| **Flow** | F-R14 |
| **Endpoint** | `GET /api/notifications?userId=$PRIYA_USER_ID` |
| **Preconditions** | TC-N01 passed. |
| **Steps** | 1. Query Priya's notifications. |
| **Expected** | 200. List with SMS messages. |
| **Status** | ⬜ |

## TC-N03: Transaction History Mirrors Unified Ledger
| Field | Value |
|--------|-------|
| **Flow** | F-R09 |
| **System** | Outbox poller (10s) → Kafka → Transaction consumer → ledger-confirm |
| **Preconditions** | TC-T01 passed. Wait 15 seconds for batch sync. |
| **Steps** | 1. Query `GET /api/transactions?accountId=$PRIYA_ACC_ID`. |
| **Expected** | Ledger entries visible. DEBIT + CREDIT for the transfer. |
| **Status** | ⬜ |

---

# ═══════════════════════════════════════════
# EMPLOYEE — Rajesh (9 test cases)
# ═══════════════════════════════════════════

### Pre-setup: Admin provisions Rajesh

## TC-E01: Admin Login (Use pre-seeded admin or provision)
| Field | Value |
|--------|-------|
| **Flow** | F-A01 → F-E01 |
| **Endpoint** | `POST /api/admin/users` then `POST /api/auth/login` |
| **Preconditions** | Admin token available. |
| **Steps** | 1. Admin creates Rajesh: `{"email":"rajesh@bank.com","password":"Admin@123","role":"EMPLOYEE"}`. 2. Rajesh logs in. |
| **Expected** | 200. Save `$RAJESH_TOKEN`. |
| **Status** | ⬜ |

## TC-E02: View Any Account (Employee)
| Field | Value |
|--------|-------|
| **Flow** | F-E02 |
| **Endpoint** | `GET /api/accounts/{accountId}` |
| **Preconditions** | TC-E01 passed. |
| **Steps** | 1. Header: `$RAJESH_TOKEN`. View Priya's account ID. |
| **Expected** | 200. Full account details. EMPLOYEE can view any account. |
| **Status** | ⬜ |

## TC-E03: Search by Account Number
| Field | Value |
|--------|-------|
| **Flow** | F-E03 |
| **Endpoint** | `GET /api/accounts/search?accountNumber=XXXXXXXXXXXX` |
| **Preconditions** | TC-E01 passed. |
| **Steps** | 1. Header: `$RAJESH_TOKEN`. Search for Priya's 12-digit account number. |
| **Expected** | 200. Returns account + owner info. |
| **Status** | ⬜ |

## TC-E04: RETAIL Tries Search by Account Number
| Field | Value |
|--------|-------|
| **Flow** | F-E03 (edge) |
| **Endpoint** | `GET /api/accounts/search?accountNumber=...` |
| **Preconditions** | TC-R04 passed. |
| **Steps** | 1. Priya's token tries search. |
| **Expected** | 403. |
| **Status** | ⬜ |

## TC-E05: Freeze Account
| Field | Value |
|--------|-------|
| **Flow** | F-E04 |
| **Endpoint** | `PATCH /api/accounts/{id}/status` |
| **Preconditions** | TC-E01 passed. |
| **Steps** | 1. Header: `$RAJESH_TOKEN`. Body: `{"status":"FROZEN"}`. Freeze Rahul's account. |
| **Expected** | 200. `success:true`. Account status is FROZEN. |
| **Status** | ⬜ |

## TC-E06: Transfer from Frozen Account
| Field | Value |
|--------|-------|
| **Flow** | F-E04 (edge) |
| **Endpoint** | `POST /api/payments/transfer` |
| **Preconditions** | TC-E05 passed (Rahul frozen). |
| **Steps** | 1. Try transfer FROM Rahul's frozen account. |
| **Expected** | 400. `INSUFFICIENT_FUNDS` or account frozen error. |
| **Status** | ⬜ |

## TC-E07: Unfreeze Account
| Field | Value |
|--------|-------|
| **Flow** | F-E05 |
| **Endpoint** | `PATCH /api/accounts/{id}/status` |
| **Preconditions** | TC-E05 passed. |
| **Steps** | 1. `{"status":"ACTIVE"}` on Rahul's account. |
| **Expected** | 200. Account active again. |
| **Status** | ⬜ |

## TC-E08: Daily Summary
| Field | Value |
|--------|-------|
| **Flow** | F-E06 |
| **Endpoint** | `GET /api/transactions/daily-summary?date=2026-08-05` |
| **Preconditions** | TC-T01 passed. Transaction Service running. |
| **Steps** | 1. Header: `$RAJESH_TOKEN`. |
| **Expected** | 200. `totalCount`, `totalVolume`, `breakdown` with DEBIT/CREDIT counts. |
| **Status** | ⬜ |

## TC-E09: Trigger System Notification
| Field | Value |
|--------|-------|
| **Flow** | F-E08 |
| **Endpoint** | `POST /api/notifications` |
| **Preconditions** | TC-E01 passed. |
| **Steps** | 1. Header: `$RAJESH_TOKEN`. Body: `{"userId":"$PRIYA_USER_ID","channel":"SMS","template":"ACCOUNT_FROZEN","message":"System maintenance at 2AM"}` |
| **Expected** | 200. Notification created. Console shows SMS. |
| **Status** | ⬜ |

---

# ═══════════════════════════════════════════
# ADMIN (4 test cases)
# ═══════════════════════════════════════════

### Pre-setup: Seed admin user or provision via initial data

## TC-A01: Provision Employee User
| Field | Value |
|--------|-------|
| **Flow** | F-A01 |
| **Endpoint** | `POST /api/admin/users` |
| **Preconditions** | Admin token available. |
| **Steps** | 1. Create auditor: `{"email":"auditor@rbi.gov.in","password":"RBI@12345","role":"AUDITOR"}` |
| **Expected** | 200. `userId` returned. |
| **Status** | ⬜ |

## TC-A02: Provision RETAIL via Admin — Blocked
| Field | Value |
|--------|-------|
| **Flow** | F-A01 (edge) |
| **Endpoint** | `POST /api/admin/users` |
| **Preconditions** | Admin token. |
| **Steps** | 1. Try `{"email":"x@test.com","password":"X","role":"RETAIL"}` via admin. |
| **Expected** | 400. "Use /api/auth/register for RETAIL/BUSINESS". |
| **Status** | ⬜ |

## TC-A03: Change User Role
| Field | Value |
|--------|-------|
| **Flow** | F-A02 |
| **Endpoint** | `PATCH /api/admin/users/{id}/role` |
| **Preconditions** | Admin token. |
| **Steps** | 1. Change some user's role. |
| **Expected** | 200. User's tokens blacklisted (must re-login). |
| **Status** | ⬜ |

## TC-A04: Deactivate User
| Field | Value |
|--------|-------|
| **Flow** | F-A03 |
| **Endpoint** | `PATCH /api/admin/users/{id}/status` |
| **Preconditions** | Admin token. |
| **Steps** | 1. `{"status":"INACTIVE"}` on a user. 2. Try to login as that user. |
| **Expected** | User login returns 403 or status-based rejection. |
| **Status** | ⬜ |

---

# ═══════════════════════════════════════════
# ACCOUNT LIFECYCLE (3 test cases)
# ═══════════════════════════════════════════

## TC-L01: Close Account — Balance Not Zero
| Field | Value |
|--------|-------|
| **Flow** | F-R19 (edge) |
| **Endpoint** | `POST /api/accounts/{id}/close` |
| **Preconditions** | Account has positive balance. |
| **Steps** | 1. Try to close an account with money in it. |
| **Expected** | 400. "Transfer remaining balance first". |
| **Status** | ⬜ |

## TC-L02: Close Last Account — Blocked (RETAIL)
| Field | Value |
|--------|-------|
| **Flow** | F-R19 (edge) |
| **Endpoint** | `POST /api/accounts/{id}/close` |
| **Preconditions** | RETAIL user with only 1 account, balance=0. |
| **Steps** | 1. Transfer all money out. 2. Try to close. |
| **Expected** | 400. "Cannot close last account". |
| **Status** | ⬜ |

## TC-L03: Close Account — Success
| Field | Value |
|--------|-------|
| **Flow** | F-R19 |
| **Endpoint** | `POST /api/accounts/{id}/close` |
| **Preconditions** | User with >1 account, one has balance=0. |
| **Steps** | 1. Close the zero-balance account. |
| **Expected** | 200. Account status = CLOSED. |
| **Status** | ⬜ |

---

# ═══════════════════════════════════════════
# SYSTEM & EDGE CASES (5 test cases)
# ═══════════════════════════════════════════

## TC-X01: Missing JWT on Protected Endpoint
| Field | Value |
|--------|-------|
| **Flow** | Token Validation |
| **Endpoint** | `GET /api/accounts/profile` |
| **Preconditions** | API Gateway running. |
| **Steps** | 1. Call without Authorization header. |
| **Expected** | 401. |
| **Status** | ⬜ |

## TC-X02: Correlation ID Propagation
| Field | Value |
|--------|-------|
| **Flow** | Token Validation |
| **Endpoint** | Any authenticated endpoint |
| **Preconditions** | Gateway + Auth running. |
| **Steps** | 1. Call with `X-Correlation-Id: test-correlation-123`. |
| **Expected** | Response header or logs show same correlation ID. |
| **Status** | ⬜ |

## TC-X03: Service Discovery — Eureka Dashboard
| Field | Value |
|--------|-------|
| **Flow** | System |
| **Endpoint** | `GET http://localhost:8761/` |
| **Preconditions** | Eureka + all services running. |
| **Steps** | 1. Open Eureka dashboard in browser. |
| **Expected** | All registered services visible. |
| **Status** | ⬜ |

## TC-X04: Config Server Serves All Configs
| Field | Value |
|--------|-------|
| **Flow** | System |
| **Endpoint** | `GET http://localhost:8888/{service}/default` |
| **Preconditions** | Config Server running. |
| **Steps** | 1. Fetch config for auth, account, payment, transaction, notification, gateway. |
| **Expected** | All 6 return valid JSON with port, datasource, etc. |
| **Status** | ⬜ |

## TC-X05: Health Endpoints
| Field | Value |
|--------|-------|
| **Flow** | System |
| **Endpoint** | `GET /actuator/health` on each service |
| **Preconditions** | All services running. |
| **Steps** | 1. Check Eureka (8761), Config (8888), Auth (8081), Account (8082), Payment (8083), Transaction (8084), Notification (8085). |
| **Expected** | All return `{"status":"UP"}`. |
| **Status** | ⬜ |

---

## Summary

| Category | Count | Passed | Failed |
|----------|:-----:|:------:|:------:|
| RETAIL (Priya) | 15 | ⬜ | ⬜ |
| BUSINESS (ABC Exports) | 14 | ⬜ | ⬜ |
| SALARY Account | 5 | ⬜ | ⬜ |
| TRANSFERS | 8 | ⬜ | ⬜ |
| NOTIFICATIONS | 3 | ⬜ | ⬜ |
| EMPLOYEE (Rajesh) | 9 | ⬜ | ⬜ |
| ADMIN | 4 | ⬜ | ⬜ |
| ACCOUNT LIFECYCLE | 3 | ⬜ | ⬜ |
| SYSTEM & EDGE | 5 | ⬜ | ⬜ |
| **TOTAL** | **66** | | |

---

> **Run order:** RETAIL → BUSINESS → SALARY → TRANSFERS → NOTIFICATIONS → EMPLOYEE → ADMIN → LIFECYCLE → SYSTEM.  
> Each category builds on data from the previous.
