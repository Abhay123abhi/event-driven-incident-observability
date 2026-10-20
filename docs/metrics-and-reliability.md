# Observability correctness and reliability

## Changes

- HTTP metrics sampled for incidents exclude Actuator endpoints to match alerts and operational dashboards.
- Error-rate percentage and error request rate are both recorded. Missing data is not represented as zero; metric errors and no-data cases become collection notes.
- Historical evidence is anchored near the initial alert firing time; `startsAt` is used when valid. Old alerts cannot be fully reconstructed if Prometheus/Loki/Tempo retention has expired.
- Incident resolution and investigation completion are independent. Resolved incidents can retain late investigation evidence without generating a second recovery notification.
- Kafka failures get two bounded retries and a dead-letter topic (`incident-investigation.DLT`). Operational replay is manual; there is no claim of exactly-once delivery.
- Prometheus directly queries current outbox pending count and oldest pending age; no new metrics storage tables are added.
- Histograms track investigation duration; Grafana shows recent rates rather than falsely claiming durable counter totals.
- Inventory trace exporter and demo response handling are aligned.

## PostgreSQL footprint

The only additional column is `incidents.investigated_at`. The existing evidence array remains bounded. There are no new tables for metrics, logs, traces, queue lag, or outbox gauges.

## Limits and safeguards

- Workload + Incident services currently run as one instance each. Multi-replica outbox claiming and multi-instance Kafka work need coordinated ownership before scaling.
- Direct SMTP delivery remains at least once. An idempotency/delivery ledger may be needed when email is enabled; disabled email does not create a durable delivery record.
- Kafka DLQ topic messages require an operator replay procedure; a message is not automatically retried forever.
- Use Prometheus and Grafana for monitoring; PostgreSQL for durable incident state and bounded evidence only.
- Run `./mvnw verify`, then `./scripts/smoke-test.ps1` and the `errors` and `latency` failure demos against the running Compose stack to validate end-to-end behavior.
