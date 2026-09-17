# AI investigation flow

The AI layer is optional and runs as a separate `ai-investigation-service`. The original deterministic incident platform continues to work when AI is disabled.

## What the AI service does

```text
Alertmanager
    ↓
incident-service
    ↓
collect Prometheus / Loki / Tempo evidence
    ↓
save deterministic incident report
    ↓
transactional outbox → Kafka `incident-ai-analysis`
    ↓
ai-investigation-service
    ├─ build retrieval query from live evidence
    ├─ Gemini embedding model → 768-d vector
    ├─ pgvector cosine search → relevant runbooks / past incidents
    ├─ build grounded prompt with live evidence + retrieved context
    ├─ Gemini generation model → structured RCA hypothesis
    └─ persist RCA, evidence, retrieved context and latency
```

Gemini is only the model provider. The RAG flow is explicit in this repository: document ingestion, embedding generation, pgvector storage, similarity search, context building and final LLM generation are separate steps.

## First-time setup

Copy `.env.example` to `.env` and set:

```text
POSTGRES_PASSWORD=...
GRAFANA_ADMIN_PASSWORD=...
AI_INVESTIGATION_ENABLED=true
GEMINI_API_KEY=...
```

The generation and embedding model names are configurable in `.env`. The default embedding dimension is 768 and matches the `vector(768)` schema.

Start the optional AI profile:

```powershell
docker compose --profile ai up --build -d --remove-orphans
docker compose ps
```

The `ai-db-init` helper creates `ai_investigation` if an older PostgreSQL volume does not already contain it, so enabling AI does not require deleting existing local incident data.

Expected additional service:

```text
ai-investigation-service   healthy   8085
```

## Seed RAG knowledge

Run:

```powershell
.\scripts\seed-ai-knowledge.ps1
```

The script embeds and stores:

- `docs/ai-knowledge/database-pool-saturation.md`
- `docs/ai-knowledge/service-latency.md`
- `docs/ai-knowledge/past-inventory-db-incident.md`

Inspect semantic retrieval directly:

```powershell
Invoke-RestMethod 'http://localhost:9000/api/ai/knowledge/search?query=database%20connections%20are%20full&service=inventory-service'
```

This endpoint is intentionally exposed for learning: it shows which documents pgvector returned and their cosine similarity scores before the LLM is involved.

## Run the complete incident → RAG → LLM flow

Use an existing failure scenario from `docs/failure-demos.md`, for example inventory latency or database pool saturation. The normal incident worker first stores the deterministic investigation. If `AI_INVESTIGATION_ENABLED=true`, the same transaction also writes an outbox event for `incident-ai-analysis`.

The outbox publisher sends that event to Kafka. `ai-investigation-service` consumes it, performs RAG, calls Gemini, and stores the structured RCA hypothesis in its own database.

Find the incident ID:

```powershell
$incident = (Invoke-RestMethod 'http://localhost:9000/api/incidents?scope=all').content[0]
$incident.id
```

Read its AI investigation:

```powershell
Invoke-RestMethod "http://localhost:9000/api/ai/investigations/$($incident.id)"
```

The response includes:

```text
probableCause
confidence
supportingEvidence
counterEvidence
affectedServices
recommendations
missingInformation
retrievedContext
latencyMs
```

## Why the services are separate

`incident-service` owns incident truth, telemetry collection and durable event publication. `ai-investigation-service` owns embeddings, RAG knowledge and AI hypotheses. AI failure therefore does not prevent alerts from being persisted, investigated deterministically or resolved.

The AI result is deliberately called a hypothesis. It is never treated as the authoritative incident state.

## Important implementation details

- Kafka delivery is at least once. The AI service checks `incident_id` before calling the LLM and the database also has a unique constraint, so completed incidents are idempotent.
- Retrieved documents are treated as untrusted reference text. The prompt explicitly tells the model not to follow instructions found inside RAG documents.
- RAG context is bounded before prompting to avoid sending unlimited document content to the model.
- The embedding dimension is configurable, but changing it requires changing the PostgreSQL vector column and re-embedding existing documents.
- Embeddings produced by different embedding model families must not be mixed in the same similarity space. Re-embed all knowledge when changing embedding models.

## Next stages

This first version intentionally stops at production-style RAG. The next layers can be added independently:

1. PostgreSQL full-text search + vector search for hybrid retrieval.
2. Reranking retrieved candidates before prompt construction.
3. Prometheus, Loki and Tempo as LLM tools for live follow-up queries.
4. MCP server exposing the observability tools.
5. Evaluation dataset generated from the existing failure-injection scenarios.
6. AI tracing for prompt, retrieval, token usage and tool calls.

Do not add autonomous remediation until the read-only investigation path is evaluated and reliable.
