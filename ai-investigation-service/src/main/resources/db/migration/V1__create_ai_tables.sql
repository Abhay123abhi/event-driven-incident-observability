CREATE EXTENSION IF NOT EXISTS vector;

CREATE TABLE knowledge_documents (
    id BIGSERIAL PRIMARY KEY,
    title VARCHAR(255) NOT NULL,
    content TEXT NOT NULL,
    service VARCHAR(100),
    document_type VARCHAR(50) NOT NULL,
    embedding vector(768) NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX idx_knowledge_service_type ON knowledge_documents(service, document_type);
CREATE INDEX idx_knowledge_embedding_hnsw ON knowledge_documents USING hnsw (embedding vector_cosine_ops);

CREATE TABLE ai_investigations (
    id BIGSERIAL PRIMARY KEY,
    incident_id VARCHAR(36) NOT NULL UNIQUE,
    service VARCHAR(100) NOT NULL,
    alert_name VARCHAR(150) NOT NULL,
    model_name VARCHAR(100) NOT NULL,
    probable_cause TEXT NOT NULL,
    confidence DOUBLE PRECISION NOT NULL,
    supporting_evidence JSONB NOT NULL DEFAULT '[]',
    counter_evidence JSONB NOT NULL DEFAULT '[]',
    affected_services JSONB NOT NULL DEFAULT '[]',
    recommendations JSONB NOT NULL DEFAULT '[]',
    missing_information JSONB NOT NULL DEFAULT '[]',
    retrieved_context JSONB NOT NULL DEFAULT '[]',
    latency_ms BIGINT NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX idx_ai_investigations_service_created ON ai_investigations(service, created_at DESC);
