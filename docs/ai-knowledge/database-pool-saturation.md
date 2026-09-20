# Database connection pool saturation

Use this runbook when request latency rises together with database connection pressure.

Strong evidence includes active Hikari connections near the configured pool maximum, connection-acquisition timeouts, and slow traces whose dominant span is a database call. Check for slow SQL, connection leaks, long transactions, and downstream database degradation before increasing the pool size.

Do not conclude pool saturation from HTTP latency alone. Missing database metrics or missing timeout logs should reduce confidence.
