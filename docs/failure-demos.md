# Failure demonstrations

[Project overview](../README.md) · [Detailed testing](testing.md)

Open the Incident Desk at http://localhost:9000, Prometheus at http://localhost:9090/alerts,
and Alertmanager at http://localhost:9093. Grafana remains at http://localhost:3000.
Run one scenario at a time and wait for recovery before the next.

## Prepare once

Set `SPRING_PROFILES_ACTIVE=observability,demo` in your existing .env, then:

```powershell
docker compose up --build -d --force-recreate inventory-service
docker compose ps inventory-service
docker compose exec postgres psql -U observe -d inventory_service -c "INSERT INTO t_inventory (sku_code, quantity) VALUES ('keyboard-001',25) ON CONFLICT (sku_code) DO UPDATE SET quantity=25;"
```

Wait until Inventory is healthy before running the seed and tests.
If /demo/failures returns 404, the demo profile is not enabled in the running container.

## Scenarios and timing

| Scenario | Run | What to observe | Recovery |
| --- | --- | --- | --- |
| Slow Inventory | `./scripts/failure-test.ps1 -Scenario latency` | P95 above 2s for 2m, with sufficient traffic | Script resets delay after 5m |
| Inventory errors | `./scripts/failure-test.ps1 -Scenario errors` | More than 5% HTTP 5xx for 2m, with sufficient traffic | Script resets errors after 5m |
| Downstream API failure | `./scripts/failure-test.ps1 -Scenario downstream` | Gateway → Order → failing Inventory; service-specific evidence | Script resets Inventory after 5m; send a fresh request |
| Service down | `docker compose stop inventory-service` | ServiceUnavailable after scrape failure persists for 3m | `docker compose start inventory-service` |
| Duplicate alert | `./scripts/smoke-test.ps1` | Same fingerprint reuses incident ID; typically completes within its 120s wait | Script submits resolved webhook |
| Missing logs | Stop Loki, run smoke test, start Loki | Report includes Loki collection failure; metrics still available | New reports can use restored Loki |
| Missing traces | Stop Tempo, run smoke test, start Tempo | Report includes Tempo collection failure | New reports can use restored Tempo |
| Kafka unavailable | Procedure below | Incident persists; unpublished outbox event waits | Publisher retries after broker starts |
| Database pool saturation | Advanced load/DB-lock test; no built-in injector | Active/max connections >90% for 2m | Release blocked work; stopping DB is a different test |

Prometheus scrapes/evaluates every 15s. New Alertmanager groups wait 30s; updates to
existing groups can wait 5m. The 2m/3m rule periods begin only when the expression
is true, so they are not guaranteed end-to-end timings. Error and latency rules
use a 5m metric window and a request rate >0.05/s. Resolution can take several minutes.

## Missing telemetry

```powershell
docker compose stop loki
./scripts/smoke-test.ps1
docker compose start loki
```

Repeat with tempo. PASS means the incident lifecycle works, not that all telemetry
is healthy. An empty trace result differs from a failed Tempo query. The report
shows collection notes separately. Existing reports are not backfilled on recovery.

## Kafka and transactional outbox

Keep Incident Service and PostgreSQL running. Stop only Kafka:

```powershell
docker compose stop broker
$fingerprint = 'outbox-' + [guid]::NewGuid().ToString('N')
$alert = @{
    status = 'firing'
    alerts = @(@{
        status = 'firing'
        fingerprint = $fingerprint
        startsAt = [DateTime]::UtcNow.ToString('o')
        labels = @{ service='inventory-service'; alertname='OutboxDemo'; severity='warning' }
        annotations = @{ summary='Broker outage demonstration' }
    })
}
Invoke-RestMethod -Method Post -Uri 'http://127.0.0.1:8084/api/incidents/webhooks/alertmanager' -ContentType 'application/json' -Body ($alert | ConvertTo-Json -Depth 6)
docker compose exec postgres psql -U observe -d incident_platform -c "SELECT aggregate_id, topic, published_at FROM outbox_events WHERE published_at IS NULL;"
docker compose start broker
```

Watch publication resume and the report appear. Then resolve it in the same shell:

```powershell
$alert.status = 'resolved'
$alert.alerts[0].status = 'resolved'
$alert.alerts[0].endsAt = [DateTime]::UtcNow.ToString('o')
Invoke-RestMethod -Method Post -Uri 'http://127.0.0.1:8084/api/incidents/webhooks/alertmanager' -ContentType 'application/json' -Body ($alert | ConvertTo-Json -Depth 6)
```

The standard smoke test waits for investigation completion; do not expect it to
pass while the broker remains stopped.

## What recovers automatically?

- Manual container stop: manually start it again.
- Injected fault: reset /demo/failures; the sample script does this in finally.
- Kafka restored: unpublished outbox events are retried.
- Source metrics recover: Alertmanager sends resolution after evaluation/group delays.
- Failed investigation: no operator replay API yet.
- Docker process crash: restart policy can restart the container; unhealthy status alone does not repair it.

The platform detects and investigates; it does not repair business logic.
INVESTIGATED means a report is ready. RESOLVED means recovery was reported.
Remove demo from .env and recreate Inventory when finished.

## Email on/off

Configure SMTP and addresses once, start the email profile, then use **Email settings**
in Incident Desk. Turning on requires complete configuration but does not test SMTP delivery.
A smoke test with sending enabled can send investigation and recovery emails.
Off consumes events without sending; it cannot recall in-flight mail.
The live setting resets to EMAIL_NOTIFICATIONS_ENABLED on restart.
