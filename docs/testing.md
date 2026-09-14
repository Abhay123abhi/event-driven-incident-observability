# Local testing

[← Project overview](../README.md)

## Run locally on Windows

Install Docker Desktop with Compose and start it. Docker builds the Java services;
you do not need Java or Maven installed for the Compose walkthrough.

From the repository directory in PowerShell:

```powershell
Copy-Item .env.example .env
```

Choose `POSTGRES_PASSWORD` and `GRAFANA_ADMIN_PASSWORD` in `.env`, then run:

```powershell
docker compose config --quiet
docker compose up --build -d --remove-orphans
docker compose ps
```

Do not overwrite an existing `.env` or change an existing database password without
also updating that database. The initial build downloads several large images.
Wait for the Java containers to become healthy before testing.

| Service | Local address |
| --- | --- |
| Incident API | http://localhost:8084/api/incidents |
| Grafana | http://localhost:3000 |
| Gateway | http://localhost:9000 |
| Prometheus | http://localhost:9090 |
| Alertmanager | http://localhost:9093 |

Everything published by Compose binds to localhost by default. PostgreSQL is exposed
on the `POSTGRES_PORT` from `.env` (default `5432`) for database inspection; Kafka
stays inside the Docker network.
Leave Kafka UI and email disabled
while learning the core workflow, especially on an 8 GB laptop. If Docker is
running out of memory, close other applications and check `docker stats`; the full
observability stack has approximately 3 GB of configured container memory without
those optional services, so allocate at least 4 GB to Docker.

## Retest the incident flow

Run the smoke test in PowerShell:

```powershell
.\scripts\smoke-test.ps1
```

It submits a synthetic webhook, checks duplicate firing alerts return the same ID,
waits for Kafka processing, checks the evidence report, then verifies resolution
and duplicate resolution. Each run uses a new fingerprint and attempts to resolve
its test incident in `finally`.

This checks intake, PostgreSQL, the outbox, Kafka, the worker, and the read API.
An unavailable telemetry source appears in the report; it is not treated as proof
of healthy service. This test does not prove Prometheus alert evaluation, SMTP
delivery, or useful telemetry content. Use the failure demonstration below for that.

See [Failure demonstrations](failure-demos.md) for the complete scenario matrix, timings, and reusable failure-test.ps1 script.

## Demonstrate a real failure and recovery

Add `SPRING_PROFILES_ACTIVE=observability,demo` to `.env` and recreate Inventory:

```powershell
docker compose up --build -d inventory-service
docker compose exec postgres psql -U observe -d inventory_service -c "INSERT INTO t_inventory (sku_code, quantity) VALUES ('keyboard-001', 25) ON CONFLICT (sku_code) DO UPDATE SET quantity = 25;"
```

Wait for Inventory to become healthy. Generate about five minutes of slow requests:

```powershell
try {
    Invoke-RestMethod -Method Post -Uri 'http://localhost:8082/demo/failures?latencyMillis=3000&failRequests=false'
    1..100 | ForEach-Object {
        Invoke-RestMethod -Uri 'http://localhost:8082/api/inventory?skuCode=keyboard-001&quantity=1' -TimeoutSec 15 | Out-Null
    }
} finally {
    Invoke-RestMethod -Method Delete -Uri 'http://localhost:8082/demo/failures'
}
```

While traffic runs, open Prometheus Alerts and watch `HighResponseLatency` become
firing. Check Alertmanager, then Grafana's incident dashboard and the Incident API.
The report should contain latency samples, with logs and traces when available.
After resetting the failure, allow the five-minute metric window and alert grouping
delays to clear before expecting `RESOLVED`. Remove `demo` from `.env` and recreate
Inventory when finished. Failure injection is for local walkthroughs only.

## Optional email and testing toggle

`EMAIL_NOTIFICATIONS_ENABLED=false` is the default. The Compose `email` profile
controls whether the notification service runs; the switch controls whether that
running service sends mail. For repeated testing, keep the consumer running and
mute sending with the switch.


Set `SMTP_HOST`, `SMTP_PORT`, `SMTP_USERNAME`, `SMTP_PASSWORD`, `NOTIFICATION_FROM`,
and `ALERT_EMAIL_TO` in `.env`. For an SMTP server without authentication or STARTTLS,
set `SMTP_AUTH=false` and `SMTP_STARTTLS=false` only for that trusted local server.

```powershell
docker compose --profile email up --build -d notification-service
docker compose logs -f notification-service
```

To turn sending **on**, set this in `.env`:

```dotenv
EMAIL_NOTIFICATIONS_ENABLED=true
```

To turn sending **off**, change it to:

```dotenv
EMAIL_NOTIFICATIONS_ENABLED=false
```

After either change, recreate only the notification container:

```powershell
docker compose --profile email up -d --force-recreate notification-service
```

Use `--build` too when first installing this code change. `docker compose restart`
does not reload environment changes. This environment variable sets the startup default. Incident Desk at port 9000
also has a live Email settings switch; live changes reset to this default on restart. Turning it off cannot recall a message already accepted by SMTP.

When off, the Kafka listener still consumes notifications, records
`incident_notification_suppressed_total` and returns normally without rendering
email or contacting SMTP. SMTP health checks are disabled too. Events successfully
consumed and committed while muted are not deliberately held for later delivery.
Stopping the consumer instead leaves a backlog subject to Kafka retention; enabling
email before that backlog is drained can send older notifications.

Test with the service running and sending off: run `.\scripts\smoke-test.ps1`, confirm
no email arrives, and inspect
`http://localhost:8083/actuator/metrics/incident.notification.suppressed`.
Then turn sending on through Incident Desk (or recreate with the environment default) and rerun the smoke test with valid
SMTP settings. Expect an investigation and recovery notification; duplicates are possible because delivery is at least once. The switch mutes all
email; it does not add throttling, digests or durable delivery deduplication. A successful
SMTP send means the server accepted the message, not that it reached the inbox.
Never commit SMTP credentials. If you previously copied a real password from the
old `.env.example`, revoke it and issue a new one.

Kafka UI is also optional:

```powershell
docker compose --profile tools up -d kafka-ui
```

Open http://localhost:8086. Prometheus still has a notification-service scrape
target when email is disabled; that target will be down. The default unavailable
service alert excludes this optional service.

## Build and test

For Java tests outside Docker, install JDK 25. The repository includes Maven Wrapper:

```powershell
.\mvnw.cmd -pl incident-service -am test
.\mvnw.cmd verify
```

On Linux/macOS use `sh ./mvnw` instead. Full reactor verification requires Docker
because workload and notification context tests use Testcontainers. GitHub Actions
runs verification, validates Compose with optional profiles, and builds the incident
container. Focused tests cover report sanitization, missing evidence, trace inclusion,
late resolution races, and duplicate result notifications.
