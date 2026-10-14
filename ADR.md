# Architecture Decision Records (ADRs)

### ADR 001: Transactional Outbox Pattern for Asynchronous Event Publishing
- **Status**: Accepted
- **Context**: Directly publishing events to Kafka during HTTP ticket creation can result in dual-write failures (e.g. database commits, but network disconnects before Kafka sends, or vice-versa).
- **Decision**: Persist ticket state and an `outbox_events` record atomically in a single local ACID transaction. An asynchronous scheduler (`OutboxPublisherScheduler`) polls pending records and safely publishes them to Kafka with at-least-once delivery.
- **Consequences**: Guarantees zero lost events; requires consumer idempotency (`processed_events`) to absorb potential duplicates.

---

### ADR 002: Apache Kafka with Retry Topics and Dead-Letter Topic (DLT)
- **Status**: Accepted
- **Context**: AI inference calls and vector search can fail transiently (rate limits, timeouts) or permanently (malformed input). Failing messages must not block other tenants' pipelines.
- **Decision**: Configure Spring Kafka `@RetryableTopic` with 3 attempts and exponential backoff (`delay = 1000ms`, `multiplier = 2.0`). Poison pills and unrecoverable failures route to `ticket-events-dlt`.
- **Consequences**: Non-blocking retry handling with full dead-letter queue isolation for monitoring.

---

### ADR 003: pgvector Extension in PostgreSQL for Multi-Tenant RAG
- **Status**: Accepted
- **Context**: We need vector similarity retrieval for help documents alongside relational tenant metadata.
- **Decision**: Use the `pgvector` extension directly in PostgreSQL rather than introducing an external standalone vector database (e.g. Pinecone, Milvus).
- **Consequences**: Eliminates distributed data sync issues. Enables transactional consistency and strict row-level tenant filtering (`WHERE tenant_id = :tenantId`) in the same SQL query with HNSW cosine distance indexing.

---

### ADR 004: Modular Monolith Architecture
- **Status**: Accepted
- **Context**: Evaluating microservices vs modular monolith for a greenfield support ticket triage platform.
- **Decision**: Build as a well-partitioned modular monolith in Spring Boot 3 / Java 21, separated into clear domains: `core`, `domain.ticket`, `domain.kb`, `domain.ai`, `domain.budget`, `worker`, and `web`.
- **Consequences**: Fast local developer feedback loop, zero RPC serialization overhead, simplified deployment with Docker Compose, and clear migration path to microservices if scaling requirements demand.

---

### ADR 005: Human-In-The-Loop Approval Workflow
- **Status**: Accepted
- **Context**: AI-generated responses can hallucinate or convey inappropriate policy answers to real customers.
- **Decision**: Enforce a strict state machine where RAG-drafted replies always transition to `AWAITING_APPROVAL`. Replies are streamed live to support agents via Server-Sent Events (SSE). An agent must explicitly call `POST /v1/dashboard/tickets/{id}/approve` before dispatch.
- **Consequences**: Zero hallucinated replies reach customers directly; continuous feedback loop enables fine-tuning and evaluation.
