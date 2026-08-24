# 🚀 Banking Microservices — Future Scope & Roadmap

> **Version:** 1.0  
> **Date:** 2026-07-18  
> **Purpose:** Capture everything we're NOT building in MVP but WILL add in future phases. This document is our long-term roadmap.

---

## Phase Overview

```
MVP (Now)          Phase 2              Phase 3               Phase 4
───────────    ───────────────    ──────────────────    ─────────────────
5 services     +4 new services    +Enterprise features   +Platform maturity
Basic flows    Rich banking       Compliance & Ops       External APIs
1 Kafka topic  Multiple topics    Advanced patterns      K8s deployment
```

---

## Phase 2 — Rich Banking Features

> **Goal:** Make this feel like a real bank, not just a transfer engine.

### New Services to Add

| # | Service | What It Does | New Patterns Learned |
|---|---------|-------------|---------------------|
| 7 | **Card Service** | Issue debit/credit cards, manage PIN, set spending limits, block/replace cards, track card transactions | State machine (card lifecycle), PCI-DSS awareness |
| 8 | **Loan Service** | Loan product configuration, credit bureau integration (mock), underwriting engine, EMI calculator, loan origination workflow, repayment tracking | Complex workflow orchestration, rules engine, external API integration |
| 9 | **Fraud Detection Service** | Real-time transaction scoring, configurable rule engine, anomaly detection (velocity checks, geo-patterns), suspicious activity alerts | Rule engine pattern, sliding window algorithms, event-driven scoring |
| 10 | **Reporting Service** | Scheduled report generation, aggregated dashboards, statement PDF generation, data export | CQRS (read-side), materialized views, scheduled jobs |

### Enhancements to Existing Services

| Service | New Capabilities |
|---------|-----------------|
| **Auth Service** | Multi-factor authentication (OTP), refresh tokens, API key management for external partners, full audit logging of every auth event |
| **Account Service** | Multi-currency accounts (USD, EUR, INR), overdraft facility, interest calculation, account statements (last 6 months) |
| **Transaction Service** | Dispute management workflow (raise → investigate → resolve), transaction categorization (food, travel, bills), full-text search via Elasticsearch |
| **Payment Service** | Bill payments, recurring/scheduled payments, NACH mandate auto-debit, forex conversion with live rates (mock), bulk payments with progress tracking |
| **Notification Service** | Real SMS (Twilio) + Email (SendGrid) integration, notification templates, user preference management (opt-in/opt-out per channel), push notifications |

### Persona Stories Unlocked in Phase 2

| Persona | New Capabilities |
|---------|-----------------|
| **Priya** | Apply for credit card, apply for personal loan, pay bills, set up auto-pay for rent, download monthly statements, dispute a wrong transaction |
| **ABC Exports** | Corporate current account, multi-user access (maker/checker roles), bulk salary disbursement via CSV, vendor payment scheduling, forex transactions |
| **Lending Team** | Define loan products, configure underwriting rules, view customer credit profiles, track loan portfolio performance |
| **Rajesh** | Fraud alert queue with review/approve workflow, configure fraud rules, real-time ops dashboard, system health monitoring |

---

## Phase 3 — Enterprise & Compliance

> **Goal:** Make this production-grade — regulatory compliance, external integrations, advanced resilience.

### New Capabilities (No New Services, But Major Upgrades)

| Area | What We Add | Patterns Learned |
|------|------------|-----------------|
| **External Partner APIs** | Loan Service exposes public API for loan aggregators (BankBazaar-style). Card Service exposes API for fintech partners. Full API versioning strategy. | API versioning, partner onboarding, API key + secret auth, rate limiting per partner, webhook callbacks |
| **Regulatory Compliance** | Immutable audit trail across all services (Kafka-based audit log → Elasticsearch). Automated SAR/STR generation. NPA classification reports. KYC expiry tracking. Data retention policies. | Event sourcing at scale, compliance as code, data archival strategies |
| **Advanced Saga** | Proper compensating transactions with retry, dead-letter queues for failed sagas, saga log for debugging | Complex saga patterns, eventual consistency handling, operational visibility |
| **Security Hardening** | Encryption at rest (all PII), secret rotation via Vault, SQL injection prevention audit, OWASP top 10 mitigation, rate limiting per user + per IP | Security-first design, secrets management, threat modeling |
| **Multi-Tenancy** | Separate tenant per corporate customer, data isolation, tenant-specific rate limits | Multi-tenancy patterns (database-per-tenant vs schema-per-tenant) |
| **Co-Lending** | Loan Service supports co-lending: Bank A funds 60%, Bank B funds 40%, profit-sharing tracked | Distributed business logic, financial reconciliation |

### RBI Auditor — Full Unlock

| Capability | Implementation |
|-----------|---------------|
| API-based data extraction | Dedicated `/api/audit/*` endpoints with read-only auditor role, machine-readable JSON/CSV export |
| Immutable transaction proof | Cryptographic hashing of ledger entries (Merkle tree), tamper-evident audit trail |
| Automated NPA verification | Reporting Service auto-classifies loans per RBI norms, generates classification proof |
| Capital Adequacy (CAR) computation | Aggregated risk-weighted asset reports |
| Access log forensics | Who viewed what PII, when, from which IP — fully queryable |
| Related-party transaction flagging | Auto-flag transactions involving directors/relatives |

---

## Phase 4 — Platform Maturity

> **Goal:** Production-ready infrastructure, observability, and developer experience.

### Infrastructure Upgrades

| Area | MVP (Now) | Phase 4 |
|------|-----------|---------|
| **Deployment** | Docker Compose (single machine) | Kubernetes (K8s) — multi-node cluster, auto-scaling, rolling updates, pod anti-affinity |
| **Service Mesh** | None | Istio/Linkerd — mTLS between services, traffic splitting, fault injection for chaos testing |
| **CI/CD** | Manual | GitHub Actions — per-service pipelines, automated tests, canary deployments |
| **Secrets** | application.yml (plain) | HashiCorp Vault — dynamic secrets, rotation, audit |
| **Logging** | Console | ELK Stack — structured JSON logs, centralized search, alerting on error patterns |
| **Monitoring** | Basic health endpoints | Prometheus + Grafana — custom business metrics, SLO dashboards, pager alerts |
| **Tracing** | Zipkin (basic) | Jaeger + OpenTelemetry — full distributed tracing with business context propagation |

### Developer Experience

| Area | What We Add |
|------|------------|
| **API Documentation** | Full OpenAPI 3.0 specs, auto-generated client SDKs (Java, Python, JS) |
| **Integration Tests** | Testcontainers for each service, end-to-end saga tests, contract tests (Pact) between services |
| **Local Dev** | Tilt / Skaffold for hot-reload across all services, one-command local setup |
| **Feature Flags** | LaunchDarkly-style toggles for gradual rollouts, A/B testing of fraud rules |
| **Chaos Engineering** | Chaos Monkey for Spring Boot — randomly kill services in staging to verify resilience |

---

## Complete Future Architecture

```
                         ┌───────────────┐
                         │  External     │
                         │  Partners     │
                         │ (BankBazaar,  │
                         │  Fintechs)    │
                         └───────┬───────┘
                                 │
┌────────────┐          ┌───────▼────────┐          ┌────────────┐
│  Mobile    │──────────►                │◄─────────│  Auditor   │
│  App       │          │  API Gateway   │          │  Portal    │
└────────────┘          │  (Kong/K8s)    │          └────────────┘
                        └───────┬────────┘
                                │
        ┌───────────────────────┼───────────────────────┐
        │                       │                       │
   ┌────▼────┐  ┌────────┐  ┌───▼────┐  ┌────────┐  ┌──▼─────┐
   │  Auth   │  │Account │  │Payment │  │  Card  │  │  Loan  │
   │ Service │  │Service │  │Service │  │Service │  │Service │
   └────┬────┘  └───┬────┘  └───┬────┘  └───┬────┘  └───┬────┘
        │           │          │           │            │
        │      ┌────▼──────────▼───────────▼────────────▼──┐
        │      │              Transaction Service           │
        │      │           (Immutable Ledger)               │
        │      └──────────────────┬────────────────────────┘
        │                         │
        │                  ┌──────▼──────┐
        │                  │    Kafka    │
        │                  │  (Event Bus)│
        │                  └──────┬──────┘
        │                         │
        │      ┌──────────────────┼──────────────────┐
        │      │                  │                  │
   ┌────▼──────▼┐    ┌───────────▼──┐    ┌──────────▼───┐
   │ Fraud      │    │ Notification │    │  Reporting    │
   │ Detection  │    │   Service    │    │   Service     │
   │ Service    │    │              │    │               │
   └────────────┘    └──────────────┘    └───────────────┘

   ┌──────────────────────────────────────────────────────────┐
   │  Infrastructure Layer (Phase 4)                          │
   │  Kubernetes | Istio | Vault | ELK | Prometheus | Jaeger │
   └──────────────────────────────────────────────────────────┘
```

---

## Database Evolution Per Phase

### Phase 2 Additions

```sql
-- Card Service DB
cards (id, user_id, card_number_hash, card_type, status, expiry, 
       daily_limit, pin_hash, issued_at, activated_at, blocked_at)

-- Loan Service DB
loan_products (id, name, interest_rate, min_amount, max_amount, 
               min_tenure, max_tenure, eligibility_rules JSON)
loan_applications (id, user_id, product_id, amount, tenure, status,
                   cibil_score, underwriting_decision, sanctioned_amount)
loans (id, application_id, disbursed_amount, emi_amount, remaining_emis,
       next_emi_date, status, dpd_counter)
emi_payments (id, loan_id, amount, due_date, paid_date, status)

-- Fraud Detection DB
fraud_rules (id, name, condition_json, action [FLAG|BLOCK], priority, enabled)
fraud_alerts (id, transaction_id, rule_id, risk_score, status [PENDING|REVIEWED|APPROVED|REJECTED], reviewer_notes)

-- Reporting Service DB
scheduled_reports (id, type, parameters, schedule_cron, last_run, status)
report_results (id, report_id, generated_at, data_json, format [JSON|CSV|PDF])
```

---

## Learning Progression: Patterns Unlocked Per Phase

| Pattern | MVP | Phase 2 | Phase 3 | Phase 4 |
|---------|-----|---------|---------|---------|
| Database per Service | ✅ | ✅ | ✅ | ✅ |
| Saga (Orchestration) | ✅ | ✅ (complex) | ✅ (DLQ) | ✅ |
| Event Sourcing | ✅ | ✅ | ✅ (Merkle) | ✅ |
| Async Messaging | ✅ | ✅ (multi-topic) | ✅ | ✅ |
| Circuit Breaker | ✅ | ✅ | ✅ | ✅ |
| Service Discovery | ✅ | ✅ | ✅ | ✅ |
| API Gateway | ✅ | ✅ | ✅ | ✅ (Kong) |
| Idempotency | ✅ | ✅ | ✅ | ✅ |
| CQRS | Light | ✅ (Reporting) | ✅ | ✅ |
| State Machine | — | ✅ (Card, Loan) | ✅ | ✅ |
| Rule Engine | — | ✅ (Fraud, Loan) | ✅ | ✅ |
| External API Integration | — | ✅ (CIBIL mock) | ✅ (Partners) | ✅ |
| API Versioning | — | — | ✅ | ✅ |
| Multi-Tenancy | — | — | ✅ | ✅ |
| Compensating Transactions | Basic | ✅ | ✅ (advanced) | ✅ |
| mTLS / Service Mesh | — | — | — | ✅ (Istio) |
| Chaos Engineering | — | — | — | ✅ |
| Contract Testing | — | — | — | ✅ (Pact) |

---

## Total Endpoint Count by Phase

| Phase | Services | ~Endpoints | Complexity |
|-------|----------|-----------|------------|
| **MVP** | 6 (incl. Gateway) | ~20 | Core CRUD + 1 Saga |
| **Phase 2** | 10 | ~55 | Card lifecycle, Loan workflow, Fraud rules |
| **Phase 3** | 10 (enhanced) | ~80 | Partner APIs, Audit APIs, Co-lending |
| **Phase 4** | 10 (hardened) | ~80 | Same endpoints, production-grade infra |

---

## When to Start Each Phase

| Phase | Prerequisite | Signal to Start |
|-------|-------------|-----------------|
| **MVP** | Nothing | Right now |
| **Phase 2** | MVP fully working, all Saga flows tested, Kafka working | MVP is stable, you've traced one full transfer end-to-end |
| **Phase 3** | Phase 2 stable, 3+ services communicating async | You're comfortable with event-driven patterns |
| **Phase 4** | Phase 3 complete, 5+ services | You want to learn K8s, want production readiness |

---

> **Reference:** See `SCOPE.md` for what we're building right now. This document is the "what's next."
