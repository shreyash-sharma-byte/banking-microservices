# Auth Service — Inbound Requests & Actions

## Self-Contained Flows

### Register
```
POST /api/auth/register { email, password }
  → Validate email format, password strength (8+, 1 upper, 1 digit, 1 special)
  → Check email UNIQUE
  → bcrypt(password) → password_hash
  → INSERT users (id=UUID, email, password_hash, role=RETAIL, status=ACTIVE)
  → Generate JWT (sub=userId, role=RETAIL, iat, exp=+1h)
  → Generate refresh token (UUID, SHA-256 hash)
  → INSERT refresh_tokens (user_id, token_hash, expires_at=+7d)
  → Return { userId, token, refreshToken, expiresIn }
```

### Register (BUSINESS)
```
POST /api/auth/register { email, password, role: "BUSINESS" }
  → Same as above but role=BUSINESS
```

### Login
```
POST /api/auth/login { email, password }
  → SELECT user WHERE email=?
  → Not found → 401 "Invalid credentials" (don't reveal which field is wrong)
  → bcrypt.verify(password, password_hash)
  → Mismatch → 401
  → Check status != LOCKED → 403
  → Generate JWT (sub=userId, role, iat, exp=+1h) — include jti (UUID) claim
  → Generate refresh token, INSERT refresh_tokens
  → Return { token, refreshToken, expiresIn: 3600 }
```

### Logout
```
POST /api/auth/logout
  → Extract jti from JWT
  → INSERT token_blacklist (jti, expires_at = JWT.exp)
  → UPDATE refresh_tokens SET revoked=TRUE WHERE user_id=JWT.sub
  → Return { success }
```

### Forgot Password
```
POST /api/auth/forgot-password { email }
  → Always return 200 (don't leak whether email exists)
  → SELECT user WHERE email=?
  → If found: generate reset token (JWT, sub=userId, exp=+15min, purpose=RESET)
  → log.info("Reset link: /reset-password?token={}", token)  // mock email
```

### Reset Password
```
POST /api/auth/reset-password { token, newPassword }
  → Verify reset JWT (signature, expiry, purpose=RESET)
  → Invalid → 400
  → Validate newPassword strength
  → UPDATE users SET password_hash=bcrypt(newPassword) WHERE id=JWT.sub
  → INSERT token_blacklist for all refresh_tokens of this user
  → UPDATE refresh_tokens SET revoked=TRUE WHERE user_id=JWT.sub
  → Return { success }
```

### Refresh Token
```
POST /api/auth/refresh { refreshToken }
  → SHA-256(refreshToken) → token_hash
  → SELECT refresh_tokens WHERE token_hash=? AND revoked=FALSE AND expires_at > NOW()
  → Not found → 401
  → Generate new JWT + new refresh token (rotate)
  → UPDATE old refresh_token SET revoked=TRUE
  → INSERT new refresh_token
  → Return { token, refreshToken, expiresIn }
```

## Inbound from Other Services

### Validate User (called by Account Service)
```
GET /api/auth/users/{userId}  (internal, no Gateway auth)
  → SELECT user WHERE id=?
  → Return { id, email, role, status }  // never return password_hash
```

### Validate JWT (called by Gateway on every request)
```
GET /api/auth/validate  (internal)
  Headers: Authorization: Bearer <token>
  → Verify JWT signature + expiry
  → Check token_blacklist WHERE jti=? AND expires_at > NOW()
  → Blacklisted → 401
  → Return { sub, role, iat, exp }
```

## Admin Operations

### Provision User
```
POST /api/admin/users { email, password, role }
  → Verify caller role = ADMIN
  → role must be EMPLOYEE, ADMIN, or AUDITOR (not RETAIL/BUSINESS)
  → INSERT users
  → Return { userId }
```

### Change Role
```
PATCH /api/admin/users/{id}/role { role }
  → Verify caller role = ADMIN
  → UPDATE users SET role=?, updated_at=NOW()
  → Blacklist all tokens for this user
  → Return { success }
```

### Deactivate User
```
PATCH /api/admin/users/{id}/status { status: "INACTIVE" }
  → Verify caller role = ADMIN
  → UPDATE users SET status='INACTIVE'
  → Blacklist all tokens for this user
  → Return { success }
```

## System Tasks

### Blacklist Cleanup (hourly)
```
DELETE FROM token_blacklist WHERE expires_at < NOW()
```

### Refresh Token Cleanup (hourly)
```
DELETE FROM refresh_tokens 
WHERE (revoked=TRUE OR expires_at < NOW()) 
AND created_at < NOW() - INTERVAL '7 days'
```

## Exposed Endpoints Summary
```
POST   /api/auth/register
POST   /api/auth/login
POST   /api/auth/logout
POST   /api/auth/refresh
POST   /api/auth/forgot-password
POST   /api/auth/reset-password
GET    /api/auth/users/{id}          (internal)
GET    /api/auth/validate            (internal)
POST   /api/admin/users              (ADMIN)
PATCH  /api/admin/users/{id}/role    (ADMIN)
PATCH  /api/admin/users/{id}/status  (ADMIN)
```
