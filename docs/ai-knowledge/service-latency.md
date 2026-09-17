# Service latency investigation

When a service reports high latency, correlate request rate, error rate, p95 latency, database connections, recent error logs, and distributed traces.

A slow downstream dependency should be supported by traces showing time concentrated in that dependency. A database hypothesis should be supported by database spans, pool pressure, or database errors. If telemetry sources are unavailable, record the missing evidence instead of treating absence as proof of health.
