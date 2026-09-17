# Historical incident: inventory database pool exhaustion

Service: inventory-service
Alert: HighResponseLatency

Observed evidence: p95 request latency rose above three seconds, active Hikari connections reached the pool limit, and application logs contained connection acquisition timeouts. Slow traces spent most of their duration waiting on PostgreSQL calls.

Confirmed cause during the historical incident: a long-running query held connections longer than expected and exhausted the application connection pool.

Resolution: optimize the slow query, verify transaction boundaries, and monitor pool pending/active connections after deployment.
