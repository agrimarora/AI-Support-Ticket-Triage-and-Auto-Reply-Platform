-- Knowledge Base Documents
CREATE TABLE knowledge_documents (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id UUID NOT NULL REFERENCES tenants(id) ON DELETE CASCADE,
    title VARCHAR(255) NOT NULL,
    content_hash VARCHAR(64) NOT NULL,
    raw_content TEXT NOT NULL,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT uq_tenant_doc_hash UNIQUE (tenant_id, content_hash)
);

CREATE INDEX idx_kb_docs_tenant ON knowledge_documents(tenant_id);

-- Knowledge Base Document Chunks with 1536-dim vector embedding
CREATE TABLE document_chunks (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id UUID NOT NULL REFERENCES tenants(id) ON DELETE CASCADE,
    document_id UUID NOT NULL REFERENCES knowledge_documents(id) ON DELETE CASCADE,
    chunk_index INT NOT NULL,
    content TEXT NOT NULL,
    embedding vector(1536),
    created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE INDEX idx_doc_chunks_tenant ON document_chunks(tenant_id);
-- HNSW cosine distance index for vector similarity retrieval
CREATE INDEX idx_doc_chunks_embedding ON document_chunks USING hnsw (embedding vector_cosine_ops);

-- Tenant Token Budgets & Rate Limit Rules
CREATE TABLE tenant_budgets (
    tenant_id UUID PRIMARY KEY REFERENCES tenants(id) ON DELETE CASCADE,
    daily_token_limit BIGINT NOT NULL DEFAULT 100000,
    used_tokens_today BIGINT NOT NULL DEFAULT 0,
    max_requests_per_minute INT NOT NULL DEFAULT 60,
    last_reset_date DATE NOT NULL DEFAULT CURRENT_DATE
);

-- Token Usage Tracking Logs
CREATE TABLE token_usage_logs (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id UUID NOT NULL REFERENCES tenants(id) ON DELETE CASCADE,
    ticket_id UUID REFERENCES tickets(id) ON DELETE SET NULL,
    operation VARCHAR(50) NOT NULL,
    model_name VARCHAR(100) NOT NULL,
    tokens_prompt INT NOT NULL DEFAULT 0,
    tokens_completion INT NOT NULL DEFAULT 0,
    tokens_total INT NOT NULL DEFAULT 0,
    cost_usd NUMERIC(10, 6) NOT NULL DEFAULT 0.000000,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE INDEX idx_token_usage_tenant_date ON token_usage_logs(tenant_id, created_at DESC);

-- Alter tickets table to store citations & feedback
ALTER TABLE tickets ADD COLUMN IF NOT EXISTS cited_chunk_ids TEXT;
ALTER TABLE tickets ADD COLUMN IF NOT EXISTS agent_feedback TEXT;
ALTER TABLE tickets ADD COLUMN IF NOT EXISTS requires_human BOOLEAN DEFAULT FALSE;
