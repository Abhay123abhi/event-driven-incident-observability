# Lightweight local run

This setup keeps the existing application flow unchanged and only applies smaller local memory/storage limits plus a localhost PostgreSQL port for inspection from IntelliJ.

## 1. Requirements

- Docker Desktop with Docker Compose
- Git
- IntelliJ IDEA (optional for browsing/debugging/database inspection)
- JDK 25 only if running Java directly from IntelliJ or Maven outside Docker

## 2. Create local environment

From the repository root in PowerShell:

```powershell
Copy-Item .env.example .env
```

Set at least:

```dotenv
POSTGRES_PASSWORD=local-observe
GRAFANA_ADMIN_PASSWORD=local-grafana
EMAIL_NOTIFICATIONS_ENABLED=false
BIND_ADDRESS=127.0.0.1
```

Do not commit `.env`.

## 3. Validate the lightweight Compose configuration

```powershell
docker compose -f docker-compose.yml -f docker-compose.local.yml config --quiet
```

## 4. Start the core stack

```powershell
docker compose -f docker-compose.yml -f docker-compose.local.yml up -d --build --remove-orphans
```

Check status:

```powershell
docker compose -f docker-compose.yml -f docker-compose.local.yml ps
```

Wait until the Java services are healthy.

## 5. Open locally

- Grafana: http://localhost:3000
- Incident API: http://localhost:8084/api/incidents
- API Gateway: http://localhost:9000
- Prometheus: http://localhost:9090
- Alertmanager: http://localhost:9093
- Loki API: http://localhost:3100
- Tempo API: http://localhost:3200
- PostgreSQL: localhost:5432

Grafana username is `admin`; password is `GRAFANA_ADMIN_PASSWORD` from `.env`.

## 6. Optional Kafka UI

Start only when you want to inspect Kafka topics/messages:

```powershell
docker compose -f docker-compose.yml -f docker-compose.local.yml --profile tools up -d kafka-ui
```

Open http://localhost:8086.

Stop it when finished:

```powershell
docker compose -f docker-compose.yml -f docker-compose.local.yml stop kafka-ui
```

## 7. Run the end-to-end smoke test

```powershell
.\scripts\smoke-test.ps1
```

This exercises alert intake, deduplication, PostgreSQL persistence, transactional outbox publication, Kafka processing, investigation result persistence, and resolution.

Watch the incident service while the test runs:

```powershell
docker compose -f docker-compose.yml -f docker-compose.local.yml logs -f incident-service
```

## 8. Inspect PostgreSQL from IntelliJ

Because the lightweight override binds PostgreSQL to localhost, add a PostgreSQL datasource in IntelliJ:

```text
Host: localhost
Port: 5432
User: observe
Password: POSTGRES_PASSWORD from .env
Database: incident_platform
```

Useful tables:

```sql
SELECT id, fingerprint, service, alert_name, status, detected_at, resolved_at
FROM incidents
ORDER BY detected_at DESC;

SELECT id, aggregate_id, topic, created_at, published_at
FROM outbox_events
ORDER BY created_at DESC;
```

## 9. Inspect resource usage

```powershell
docker stats
```

The local override reduces Java container limits, Kafka heap, Prometheus retention, and memory limits for Grafana/Loki/Tempo/Alertmanager/PostgreSQL.

## 10. Stop containers

Keep data volumes:

```powershell
docker compose -f docker-compose.yml -f docker-compose.local.yml down
```

Remove containers and local data for a completely clean reset:

```powershell
docker compose -f docker-compose.yml -f docker-compose.local.yml down -v
```

Use `down -v` only when you intentionally want to delete PostgreSQL, Kafka, Grafana, Prometheus, Loki, Tempo and Alertmanager local data.
