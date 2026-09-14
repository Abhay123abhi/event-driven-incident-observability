# Operations & retention

[← Project overview](../README.md)

## Upgrading from the old module

The module and Compose service are now `incident-service`. Stop the previous stack
with `docker compose --profile email down` before switching branches, then rebuild
with `--remove-orphans`. Keep database volumes; do not use `down -v` to upgrade.

Back up `incident_platform` first if its history matters. Flyway V1 is unchanged;
V2 removes the obsolete `probable_root_cause` and `confidence` columns. Those two
fields are also removed from the API and new notification events. Existing evidence,
incident IDs, outbox entries, and lifecycle history are retained. The notification
consumer ignores obsolete fields in queued older events. Upgrade both Java services
together; rolling back to the old module requires restoring the database backup.

Remove old provider variables from your local `.env`; they are no longer used.

## Storage and growing data volumes

Raw error logs, incident records and Kafka work events are different data types.
A log line does not automatically create an incident: an alert rule must fire and
Alertmanager must forward it. Multiple distinct fingerprints can still create many
incidents; this is not cross-alert incident correlation.

| Data | Persistent store | Retention clock and current behavior |
| --- | --- | --- |
| Resolved incident and its evidence report | PostgreSQL `incidents`, in `postgres-data` | Eligible for deletion **30 days after `resolved_at`**, not 30 days after creation. The report is stored on the same row and is deleted with it. |
| Active incident, including completed investigation awaiting recovery | PostgreSQL `incidents` | **No automatic expiry** until the incident becomes `RESOLVED`. `INVESTIGATED` does not mean resolved. |
| Failed investigation | PostgreSQL `incidents`, status `INVESTIGATION_FAILED` | **No automatic expiry** while unresolved. Its exception is logged; there is no separate durable failed-event/DLQ record. If later resolved, the 30-day resolved retention applies. |
| Published investigation or notification outbox event | PostgreSQL `outbox_events`, in `postgres-data` | Eligible for deletion **7 days after `published_at`**. Publication means Kafka acknowledged the send and the application saved that timestamp; it does not confirm investigation completion or email delivery. |
| Unpublished outbox event | PostgreSQL `outbox_events` | **No automatic expiry or backlog cap**. Kept for publication retries until successfully marked published. |
| Investigation and notification topic messages | Kafka, in `kafka-data` | Local broker retention is limited to **6 hours** and approximately **128 MB per partition**. PostgreSQL's 7-day outbox policy does not control Kafka. Consumed messages are not immediately deleted. |
| Raw application logs | Loki, in `loki-data` | **No explicit project retention policy**. Uses the image's local configuration; do not assume logs are removed after 7 or 30 days. |
| Raw trace data | Tempo, in `tempo-data` | **No explicit project retention policy**. Local block storage uses component defaults; no guaranteed project-level duration or disk budget is declared. |
| Metrics, including failure and notification counters | Prometheus, in `prometheus-data` | Local retention is limited to **24 hours** and **128 MB**. Prometheus applies whichever limit is reached first. In-process counters are not a durable event history. |
| Container stdout/stderr | Docker host logging storage | Compose rotates each container's JSON logs at **10 MB**, keeping **3 files**. This storage is separate from Loki. |
| Sent email | Configured SMTP server and recipient mailbox | Provider/mailbox retention applies. The application has **no persistent email-delivery ledger**. Muted events are consumed without sending; Kafka retention still operates independently. |

### Persistence versus retention

Persistence means data survives an application/container restart because it is
written to a database or disk-backed volume. Retention determines when stored data
becomes eligible for cleanup. Neither is a backup or a guarantee against disk loss.

The five-minute evidence collection window is a **query window**, not an expiry.
For example, a report can contain five minutes of sampled telemetry and remain
stored for weeks. The selected log text and trace summaries live in PostgreSQL;
opening the full trace later still depends on Tempo retaining its original data.

### Example: one incident over time

- **Day 0:** an alert creates an incident and an investigation outbox event.
- **Day 0:** Kafka acknowledges that event and `published_at` is saved. Its outbox
  cleanup clock starts. The worker stores a report and queues a separate notification
  event, with its own publication clock.
- **Day 2:** a recovery webhook resolves the incident. Its 30-day clock starts now.
- **After day 7:** an event published on day 0 becomes eligible for outbox cleanup,
  even if the incident itself remains stored.
- **After day 32:** the incident resolved on day 2, including its evidence snapshot,
  becomes eligible for cleanup.

If recovery never arrives, the incident does not automatically disappear on day 30.
If Kafka publication never succeeds, the unpublished event does not automatically
disappear on day 7. These cases require backlog monitoring and an operational policy.

### Changing the database retention periods

Set the following in `.env`:

```dotenv
INCIDENT_RESOLVED_RETENTION_DAYS=30
INCIDENT_OUTBOX_RETENTION_DAYS=7
```

Then apply the settings to the incident container:

```powershell
docker compose up -d --force-recreate incident-service
```

Supported ranges are 1–3,650 days for resolved incidents and 1–365 days for published
outbox entries. Lowering a period also affects existing records: older eligible
records can be deleted on the next cleanup pass. Back up history you need first.
These settings do not change Kafka, Loki, Tempo, Prometheus or mailbox retention.

Docker named volumes survive container restarts and `docker compose down`. On
Docker Desktop they consume host-backed storage inside Docker's Linux environment.
They are persistence, not backups, remote storage or unlimited capacity.

### How much evidence is copied into an incident?

The worker queries a five-minute window ending when collection starts. It requests
up to 10 log lines by default (configurable up to 50), then the report selects up to
3 error samples and 5 trace summaries alongside metrics and collection notes.
Report lists are capped at 20 entries each, and each entry is capped at 1,000
characters. These are character/list limits, not a guaranteed byte size. Full logs
and trace spans are not copied into PostgreSQL. The saved snapshot can outlive the
raw telemetry, but opening an old trace requires it still to exist in Tempo.

Database cleanup runs with a one-hour fixed delay after completion, initially five
minutes after startup. Records become eligible at the configured age; deletion is
not an exact TTL deadline and requires a healthy running incident service. Deleting
rows also does not guarantee the database volume immediately shrinks on disk.

### What happens during a failure storm?

Repeated firing webhooks for one active fingerprint reuse its incident. Distinct
fingerprints create separate incidents and outbox events. The publisher reads up
to 100 pending events per pass; that bounds a publishing batch, not the total queue.
Kafka decouples intake from investigation, so temporary bursts can accumulate as
consumer lag. If incoming work keeps exceeding processing capacity, the backlog
and investigation delay continue to grow.

If Kafka is down, unpublished outbox entries accumulate in PostgreSQL. If workers
are down, Kafka messages accumulate subject to Kafka retention. Retention can remove
messages before a slow or stopped consumer processes them. Queueing therefore buys
time; it does not guarantee unlimited buffering or prevent disk exhaustion.

The current setup handles a local demo and provides some bounded data handling.
It has no measured high-volume capacity, per-service intake rate limit, complete
storage budget, automatic archival or multi-instance worker coordination.

### Next steps before sustained high-volume use

These are proposed improvements, not enabled features:

1. Configure and verify Loki compactor retention and explicit Tempo retention.
   Revisit the short local Kafka, Prometheus and Docker-log limits for the expected
   production workload. Preserve free-space headroom; retention thresholds are not
   exact disk quotas.
2. Monitor disk usage, oldest unpublished outbox age, pending outbox count, Kafka
   consumer lag and investigation duration. Define operational thresholds before
   accepting more load.
3. Set an intake rate limit and an explicit overload response/retry policy. Add
   bounded retries, a dead-letter queue and controlled replay for failed work.
4. Define ownership and review/archival rules for unresolved and failed incidents.
   Never silently delete pending investigations just to reduce disk use.
5. Batch database cleanup and measure query/index performance. Consider time
   partitioning, archival or object storage only when measured volume warrants it.
6. Load-test the pipeline, then add partitions and workers together with safe
   outbox claiming and durable idempotency before running multiple instances.

For capacity planning, estimate each store separately. For example, assuming
1,000 distinct resolved incidents/day, 10 KB per stored report and 30 retained days,
report content alone would be about 300 MB. This is illustrative, not a benchmark;
indexes, outbox payloads, unresolved records, PostgreSQL overhead and raw telemetry
are additional. Raw log volume can exceed incident-report volume by a large margin.

Loki requires explicit retention configuration; filesystem storage does not delete
logs simply because the disk is nearly full. See the [Loki retention documentation](https://grafana.com/docs/loki/latest/operations/storage/retention/)
and [filesystem storage behavior](https://grafana.com/docs/loki/latest/configure/storage/).
Kafka byte retention applies per partition, not to the entire cluster; see
[Kafka topic configuration](https://kafka.apache.org/41/configuration/topic-configs/).
Prometheus also needs room for ongoing writes and compaction beyond retained blocks;
see [Prometheus storage guidance](https://prometheus.io/docs/prometheus/latest/storage/).

## Limits and follow-up work

- The API accepts `scope=active|resolved|all`, `page`, and `size`, capped at 100.
- Reports normalize text and bound lists; telemetry collection defaults to 10 log
  lines, with a configurable limit of 50. Common secrets and email addresses are redacted.
- Database expiry is eligibility-based: 30 days after resolution and 7 days after
  successful outbox publication. See the storage table for records that never
  automatically expire and for the independent telemetry retention policies.
- Sequential repeated alerts are deduplicated by active fingerprint. A database
  constraint prevents concurrent duplicate rows, but concurrent webhook requests
  can still need retry. Resolved-before-firing and stale alert episodes need further work.
- The outbox provides at-least-once publication, not exactly-once delivery. Run one
  incident-service instance with this configuration. Multi-replica claiming and
  durable notification deduplication are not implemented.
- Investigation errors are stored as `INVESTIGATION_FAILED`. There is no DLQ/replay
  interface yet. Check service logs; after fixing the cause, reset and retrigger the
  demo. Partial telemetry failures remain visible in the report.
- The collection window ends at worker execution time. Replaying an old alert does
  not reconstruct its historical window. Deployment correlation and richer incident
  lifecycle management are follow-up work.

## Local or deployed?

Local is sufficient for development and a portfolio walkthrough. Include the
architecture, passing test output, and a short failure-to-recovery recording.
A permanent public deployment is optional.

For a hosted demonstration, a Linux VM running Docker Compose is the simplest
extension of this setup. DigitalOcean Docker Droplets or Hetzner Cloud are options.
As an initial planning estimate, use 4 vCPU / 8 GB RAM and measure actual usage;
this repository does not include a capacity benchmark. Build services sequentially
if memory is tight. This is a backend stack, not a static site suitable for Netlify.

Start with a private demo accessed through SSH port forwarding; keep the localhost
bindings. Do not expose Kafka, PostgreSQL, telemetry APIs, or demo failure endpoints.
Public access requires authentication, TLS, firewall rules, secret management,
backups and a recovery plan. This single-node Compose stack is not highly available.

## Useful commands

```powershell
docker compose logs --tail=200 incident-service
docker compose logs -f inventory-service order-service
docker compose ps
docker stats
docker compose --profile email --profile tools down
```

`down` stops containers and keeps volumes. Avoid `down -v` unless you intentionally
want to delete the local databases and telemetry history.
