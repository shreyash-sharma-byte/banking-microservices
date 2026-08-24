CREATE SEQUENCE IF NOT EXISTS account_number_seq START 1;

CREATE TABLE IF NOT EXISTS retail_profiles (
    id              UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id         UUID UNIQUE NOT NULL,
    full_name       VARCHAR(255) NOT NULL,
    phone           VARCHAR(20) NOT NULL,
    date_of_birth   DATE NOT NULL,
    pan_number      VARCHAR(10) NOT NULL,
    aadhaar_last4   VARCHAR(4),
    address         TEXT,
    created_at      TIMESTAMP DEFAULT NOW()
);

CREATE TABLE IF NOT EXISTS business_profiles (
    id              UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id         UUID UNIQUE NOT NULL,
    company_name    VARCHAR(255) NOT NULL,
    contact_name    VARCHAR(255) NOT NULL,
    contact_phone   VARCHAR(20) NOT NULL,
    gst_number      VARCHAR(15) NOT NULL,
    pan_number      VARCHAR(10) NOT NULL,
    business_type   VARCHAR(30) NOT NULL CHECK (business_type IN ('PROPRIETORSHIP','PARTNERSHIP','PVT_LTD','LLP')),
    registered_addr TEXT NOT NULL,
    annual_turnover DECIMAL(15,2),
    created_at      TIMESTAMP DEFAULT NOW()
);

CREATE TABLE IF NOT EXISTS accounts (
    id                  UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    owner_id            UUID NOT NULL,
    account_number      VARCHAR(12) UNIQUE NOT NULL,
    account_type        VARCHAR(20) NOT NULL CHECK (account_type IN ('SAVINGS','SALARY','CURRENT')),
    label               VARCHAR(100) NOT NULL,
    balance             DECIMAL(15,2) NOT NULL DEFAULT 0 CHECK (balance >= 0),
    employer_business_id UUID,
    status              VARCHAR(20) DEFAULT 'ACTIVE' CHECK (status IN ('ACTIVE','FROZEN','CLOSED')),
    created_at          TIMESTAMP DEFAULT NOW(),
    updated_at          TIMESTAMP DEFAULT NOW(),
    UNIQUE(owner_id, label)
);

CREATE TABLE IF NOT EXISTS audit_log (
    id              BIGSERIAL,
    account_id      UUID NOT NULL,
    action          VARCHAR(50) NOT NULL,
    amount          DECIMAL(15,2),
    balance_after   DECIMAL(15,2),
    reference       VARCHAR(255),
    performed_by    UUID,
    correlation_id  VARCHAR(36),
    created_at      TIMESTAMP DEFAULT NOW(),
    PRIMARY KEY (id, created_at)
) PARTITION BY RANGE (created_at);

CREATE TABLE IF NOT EXISTS audit_log_2026_08 PARTITION OF audit_log
    FOR VALUES FROM ('2026-08-01') TO ('2026-09-01');

CREATE TABLE IF NOT EXISTS business_employees (
    id              UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    business_id     UUID NOT NULL,
    employee_id     UUID NOT NULL,
    employee_code   VARCHAR(50),
    status          VARCHAR(20) DEFAULT 'ACTIVE' CHECK (status IN ('ACTIVE','INACTIVE')),
    created_at      TIMESTAMP DEFAULT NOW(),
    UNIQUE(business_id, employee_id)
);

CREATE INDEX IF NOT EXISTS idx_accounts_owner ON accounts(owner_id);
CREATE INDEX IF NOT EXISTS idx_accounts_number ON accounts(account_number);
CREATE INDEX IF NOT EXISTS idx_retail_user ON retail_profiles(user_id);
CREATE INDEX IF NOT EXISTS idx_business_user ON business_profiles(user_id);
CREATE INDEX IF NOT EXISTS idx_be_business ON business_employees(business_id);
CREATE INDEX IF NOT EXISTS idx_be_employee ON business_employees(employee_id);
