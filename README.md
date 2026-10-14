# AI Support Ticket Triage and Auto-Reply Platform

[![Build Status](https://img.shields.io/badge/build-passing-brightgreen)]()
[![Tests](https://img.shields.io/badge/tests-39%20passing-brightgreen)]()
[![Coverage](https://img.shields.io/badge/coverage-95%25-brightgreen)]()
[![Java](https://img.shields.io/badge/Java-21-orange)]()
[![Spring Boot](https://img.shields.io/badge/Spring%20Boot-3.3.4-green)]()

A multi-tenant backend platform built with **Java 21** and **Spring Boot 3** that ingests customer support tickets, classifies them asynchronously using an event-driven Kafka pipeline, drafts contextual RAG auto-replies from help documents, and routes updates to support agent dashboards with human-in-the-loop approval.

**✅ Phase 1 + Phase 2 Complete with 39 Integration Tests**

---

## Architecture Overview

```
                      +-------------------------------------------------------------+
                      |                      API Gateway / Security                 |
                      |          Tenant Auth (Hashed Keys), Rate Limiter           |
                      +------------------------------+------------------------------+
                                                     |
                                                     v
                                          +---------------------+
                                          |  POST /v1/tickets   |
                                          +----------+----------+
                                                     |
                                                     v
                                          +---------------------+
                                          | PostgreSQL (DB)     |
                                          | + Transaction Outbox|
                                          +----------+----------+
                                                     |
                                                     v
                                          +---------------------+
                                          | Kafka / Message Queue|
                                          +----------+----------+
                                                     |
                                  +------------------+------------------+
                                  |                                     |
                                  v                                     v
                       +----------------------+             +----------------------+
                       | Classification Worker|             | Retry / DLT Topics   |
                       +----------+-----------+             +----------------------+
                                  |
                                  v
                        +-------------------+
                        | Spring AI / LLM   | <---> Redis Cache & Token Budgeting
                        +---------+---------+
                                  |
                                  v
                       +----------------------+
                       | RAG (pgvector Search)|
                       +----------+-----------+
                                  |
                                  v
                       +----------------------+
                       | Agent Dashboard (SSE)|
                       | & Human Approval     |
                       +----------+-----------+
```

---

## Key Backend & AI Reliability Patterns

1. **Transactional Outbox Pattern**:
   - Ticket ingestion atomically writes both the ticket record and an outbox event (`TICKET_CREATED`) to PostgreSQL in a single database transaction.
   - An asynchronous publisher (`OutboxPublisherScheduler`) polls pending outbox records and dispatches them to Kafka, preventing lost events or dual-write inconsistencies.

2. **Idempotency Guarantees**:
   - **API Idempotency**: `POST /v1/tickets` accepts an `Idempotency-Key` header. Submitting duplicate keys returns the original ticket response without duplicate database inserts or outbox events.
   - **Consumer Idempotency**: The Kafka consumer tracks event processing in a `processed_events` table indexed by `(event_id, consumer_group)`. Duplicate Kafka message deliveries are safely skipped.

3. **Multi-Tenant Isolation**:
   - Requests require an `X-API-Key` header. Keys are stored as SHA-256 hashes in `api_keys` and cached in Redis.
   - `TenantAuthenticationFilter` sets `TenantContext` via `ThreadLocal` for request scoping.
   - JPA repositories enforce tenant-scoped filters (`findByTenantId`, `findByIdAndTenantId`).

4. **Non-Blocking Error Handling & DLT Routing**:
   - `@RetryableTopic` configures exponential backoff retries for transient classification failures.
   - Unrecoverable messages land safely in `ticket-events-dlt` for inspection and alerting.

---

## Tech Stack

- **Language & Framework**: Java 21 LTS, Spring Boot 3.3.4
- **Database & Migration**: PostgreSQL 16 with `pgvector` extension, Flyway
- **Messaging**: Apache Kafka (KRaft Mode)
- **Caching & Locks**: Redis 7
- **Fault Tolerance**: Resilience4j
- **Build Tool**: Maven (`./mvnw.cmd` wrapper included)

---

## Quickstart & Running Locally

### 1. Start Infrastructure (PostgreSQL, Kafka, Redis)
Make sure Docker Desktop is running, then start the services:
```bash
docker compose up -d
```

### 2. Build and Run Application
```bash
./mvnw.cmd clean spring-boot:run
```

### 3. Seed a Tenant & Obtain API Key
```bash
curl -X POST http://localhost:8081/v1/admin/tenants \
  -H "Content-Type: application/json" \
  -d '{"name": "Acme Corp", "slug": "acme"}'
```
*Response:*
```json
{
  "tenantId": "c39364cc-c06a-4aaa-8f0e-5fffe130cf14",
  "name": "Acme Corp",
  "slug": "acme",
  "rawApiKey": "tk_alpha_1234567890abcdef"
}
```

### 4. Post a Ticket
```bash
curl -X POST http://localhost:8081/v1/tickets \
  -H "X-API-Key: tk_alpha_1234567890abcdef" \
  -H "Idempotency-Key: request-key-001" \
  -H "Content-Type: application/json" \
  -d '{
    "customerId": "cust-100",
    "title": "Critical bug: Application crash on login",
    "description": "The app freezes and closes immediately when tapping sign-in"
  }'
```

### 5. Check Classified Ticket Status
```bash
curl -X GET http://localhost:8081/v1/tickets \
  -H "X-API-Key: tk_alpha_1234567890abcdef"
```

---

## Running Tests

### Quick Unit Tests (No Docker Required)
```bash
./mvnw.cmd test -Dtest="GuardrailServiceTest,TenantBudgetTest,ClassificationWorkerTest,TenantAuthenticationFilterTest,TicketServiceTest"
```
**Tests:** 10 test cases in ~5 seconds  
**Coverage:** PII masking, prompt injection, token budgets, classification, idempotency

### Full Integration Test Suite (Requires Docker)
```bash
./mvnw.cmd test
```
**Tests:** 39 test cases across 11 test files  
**Time:** ~3-4 minutes (includes Testcontainers startup)  
**Coverage:** Full pipeline, RAG retrieval, circuit breakers, rate limiting, tenant isolation, DLT routing

### Test Coverage Summary

| Phase | Feature | Tests | Status |
|-------|---------|-------|--------|
| **Phase 1** | Transactional Outbox | ✅ | Proven |
| | API Idempotency | ✅ | Proven |
| | Consumer Idempotency | ✅ | Proven |
| | Tenant Isolation | ✅ | Proven |
| | Kafka Retry & DLT | ✅ | Proven |
| **Phase 2** | RAG Retrieval | ✅ | Proven |
| | Redis Caching | ✅ | Proven |
| | Circuit Breaker Fallback | ✅ | Proven |
| | Token Budget Enforcement | ✅ | Proven |
| | Rate Limiting | ✅ | Proven |
| | PII Masking | ✅ | Proven |
| | Prompt Injection Detection | ✅ | Proven |
| | Knowledge Base Deduplication | ✅ | Proven |
| | Human-in-the-Loop Approval | ✅ | Proven |

**See:** `PHASE2_COMPLETION_REPORT.md` for detailed test documentation

---
