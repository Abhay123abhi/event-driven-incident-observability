# Core reliability improvement tracker

Scope: default (non-AI) Docker Compose stack only. Do not change the separate AI branch.

## Status and evidence

| Priority | Task | Status | Validation |
| --- | --- | --- | --- |
| P0 | Verify full Prometheus to incident recovery path | Script ready; runtime check pending | `scripts/verify-pipeline.ps1`; needs local failure-test execution |
| P1 | Repair Loki4j 2.x Logback appender configuration | Merged in PR #50; runtime check pending | Restart containers and check Loki query and logback startup warnings |
| P1 | Concurrent deduplication and out-of-order firing/resolution | Concurrent insert recovery merged in PR #51; out-of-order episodes pending | Concurrency and lifecycle integration tests |
| P1 | Controlled DLT inspection and replay | Planned | Kafka outage/retry/replay test without duplicate notifications |
| P1 | Fast critical and sustained error-rate alerting | Planned | promtool rules tests; avoid duplicate incident storms |
| P2 | Pipeline-specific alerts and dashboard | Planned | Alert coverage and live dashboard data |
| P2 | Loki/Tempo retention | Planned | Config validation and disk-budget test |
| P2 | Runtime circuit breaker/retry behavior | Planned | Failure injection and bounded retry/latency assertions |

## Loki4j logging fix

PR #50 on main migrates the unsupported Loki4j 2.x `<format>` section to
`<labels>`, `<structuredMetadata>`, and a JSON message layout. All five Java
services use a low-cardinality `service` label. Error messages should be searchable
with `{service="inventory-service"}`. Use `scripts/verify-loki.ps1` from main to
check ingestion; startup-only checks do not prove end-to-end logging.

After checking out the branch and building the stack:

```powershell
docker compose up -d --build --remove-orphans
docker compose ps
docker compose logs --tail=100 inventory-service incident-service
```

There should be no `Ignoring unknown property [format]` warning.
To verify Loki ingestion, generate some Inventory traffic (even a successful GET),
then query its recent logs:

```powershell
$query = '{service="inventory-service"}'
$uri = 'http://127.0.0.1:3100/loki/api/v1/query_range?query=' +
    [uri]::EscapeDataString($query) + '&limit=10'
(Invoke-RestMethod -Uri $uri).data.result | ConvertTo-Json -Depth 6
```

If the Loki response has no streams, first check the inventory startup logs and
Loki availability, then retry after a few seconds of batch flushing.

## Real end-to-end verification

Run the basic direct-webhook smoke test first:

```powershell
.\scripts\smoke-test.ps1
```

Enable the demo profile in `.env`, recreate the Inventory container, and confirm
it is **healthy** before injecting an error. Seed its test SKU if needed:

```powershell
docker compose up -d --force-recreate inventory-service
docker compose ps inventory-service
docker compose exec postgres psql -U observe -d inventory_service -c "INSERT INTO t_inventory (sku_code, quantity) VALUES ('keyboard-001',25) ON CONFLICT (sku_code) DO UPDATE SET quantity=25;"
.\scripts\failure-test.ps1 -Scenario errors
```

The failure scenario deliberately runs for five minutes and resets its injected
fault in a `finally` block. Once Prometheus evaluation, Alertmanager grouping,
and Kafka investigation complete, validate the resulting incident:

```powershell
.\scripts\verify-pipeline.ps1
```

After the alert is cleared and a recovery notification has been processed:

```powershell
.\scripts\verify-pipeline.ps1 -RequireResolved
```

The verifier checks a recent incident with a known alert name and its completed
report in the incident API. It does not post a webhook. Matching a known alert
name is **not proof of origin** by itself (a synthetic webhook could use that
name); inspect Prometheus/Alertmanager during the real failure test for provenance.
Review Prometheus's alert history, Alertmanager delivery errors, and the outbox
database separately when investigating end-to-end failures. This check is not a
complete trace of every individual Kafka acknowledgement.

## Merge history

- PR #50: Loki4j config and JSON-ingestion verification already on main.
- PR #51: concurrent active-fingerprint insert handling already on main.
- This PR: only the incremental pipeline-verifier script and reliability tracker;
  redundant logging changes were removed while resolving conflicts.

## Non-goals

- No AI model, AI service, embeddings, or RAG changes.
- No public production deployment claims.
- No automatic merging or deletion of local test volumes.
- Do not add a second overlapping critical-error rule without designing incident
  correlation and explicit test cases first.

## Follow-up documentation

When later work lands, update this tracker, README's verification steps, the
failure matrix in `docs/failure-demos.md`, and `docs/operations.md` with
configuration, expected behavior, limitations, and reproducible commands.
