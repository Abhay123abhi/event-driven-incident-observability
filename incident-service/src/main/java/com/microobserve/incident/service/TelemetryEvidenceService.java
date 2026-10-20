package com.microobserve.incident.service;

import com.microobserve.incident.api.AlertmanagerWebhook;
import com.microobserve.incident.config.IncidentProperties;
import com.microobserve.incident.model.IncidentEvidence;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.JsonNode;

import java.net.http.HttpClient;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Service
public class TelemetryEvidenceService {

    private static final Logger log = LoggerFactory.getLogger(TelemetryEvidenceService.class);

    private final RestClient restClient;
    private final IncidentProperties properties;

    public TelemetryEvidenceService(RestClient.Builder restClient, IncidentProperties properties) {
        var httpClient = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3)).build();
        var requestFactory = new JdkClientHttpRequestFactory(httpClient);
        requestFactory.setReadTimeout(Duration.ofSeconds(5));
        this.restClient = restClient.requestFactory(requestFactory).build();
        this.properties = properties;
    }

    public IncidentEvidence collect(AlertmanagerWebhook.Alert alert) {
        String service = alert.service();
        var metrics = new LinkedHashMap<String, Double>();
        var notes = new ArrayList<String>();
        Instant end = evidenceTime(alert, notes);
        Instant start = end.minus(Duration.ofMinutes(5));
        notes.add("Telemetry collection window: " + start + " to " + end);

        String requests = "http_server_requests_seconds_count{job=\"%s\",uri!~\"/actuator/.*\"}".formatted(service);
        String errors = "http_server_requests_seconds_count{job=\"%s\",status=~\"5..\",uri!~\"/actuator/.*\"}".formatted(service);
        queryMetric(metrics, notes, end, "request_rate", "sum(rate(" + requests + "[5m]))");
        queryMetric(metrics, notes, end, "error_requests_per_second", "(sum(rate(" + errors + "[5m])) or vector(0))");
        queryMetric(metrics, notes, end, "error_rate_percent",
                "100 * (sum(rate(" + errors + "[5m])) or vector(0)) / clamp_min(sum(rate(" + requests + "[5m])), 0.001)");
        queryMetric(metrics, notes, end, "p95_latency_seconds",
                "histogram_quantile(0.95,sum by(le)(rate(http_server_requests_seconds_bucket{job=\"%s\",uri!~\"/actuator/.*\"}[5m])))".formatted(service));
        if (!"api-gateway".equals(service) && !"notification-service".equals(service)) {
            queryMetric(metrics, notes, end, "active_db_connections",
                    "sum(hikaricp_connections_active{job=\"%s\"})".formatted(service));
        }

        return new IncidentEvidence(service, alert.alertName(), alert.severity(), Map.copyOf(metrics),
                fetchLogs(service, start, end, notes), fetchTraces(service, start, end, notes), List.copyOf(notes));
    }

    private Instant evidenceTime(AlertmanagerWebhook.Alert alert, List<String> notes) {
        String startsAt = alert.startsAt();
        Instant now = Instant.now();
        if (startsAt == null || startsAt.isBlank()) {
            notes.add("Alert startsAt missing; using investigation time.");
            return now;
        }
        try {
            Instant alertStart = Instant.parse(startsAt);
            if (!alertStart.isAfter(now)) {
                // Alert rules persist for two minutes (three minutes for ServiceUnavailable).
                // Query the historical window when the condition first became firing.
                Duration pending = "ServiceUnavailable".equals(alert.alertName())
                        ? Duration.ofMinutes(3) : Duration.ofMinutes(2);
                Instant firingWindow = alertStart.plus(pending);
                return firingWindow.isAfter(now) ? now : firingWindow;
            }
            notes.add("Alert startsAt is in the future; using investigation time.");
        } catch (java.time.format.DateTimeParseException exception) {
            notes.add("Alert startsAt invalid; using investigation time.");
        }
        return now;
    }

    private void queryMetric(Map<String, Double> metrics, List<String> notes, Instant end, String name, String query) {
        try {
            JsonNode response = restClient.get().uri(properties.prometheusUrl() + "/api/v1/query?query={query}&time={time}", query, end.getEpochSecond())
                    .retrieve().body(JsonNode.class);
            if (!"success".equals(response.path("status").asText())) {
                throw new IllegalStateException("Prometheus query failed");
            }
            JsonNode result = response.path("data").path("result");
            if (result.isEmpty()) {
                notes.add("Metric NO_DATA: " + name);
                return;
            }
            double value = Double.parseDouble(result.get(0).path("value").get(1).asText());
            if (Double.isFinite(value)) metrics.put(name, value);
            else notes.add("Metric NO_DATA: " + name);
        } catch (Exception exception) {
            notes.add("Metric QUERY_FAILED: " + name);
            log.warn("Unable to collect metric {}: {}", name, exception.getMessage());
        }
    }

    private List<String> fetchLogs(String service, Instant start, Instant end, List<String> notes) {
        try {
            String query = "{service=\"%s\"} |~ \"(?i)error|exception|timeout\"".formatted(service);
            JsonNode response = restClient.get()
                    .uri(properties.lokiUrl() + "/loki/api/v1/query_range?query={query}&limit={limit}&start={start}&end={end}",
                            query, properties.maximumLogLines(), start.toString(), end.toString())
                    .retrieve().body(JsonNode.class);

            var lines = new ArrayList<String>();
            response.path("data").path("result").forEach(stream -> stream.path("values")
                    .forEach(value -> lines.add(redact(value.get(1).asText()))));
            return lines.stream().distinct().limit(properties.maximumLogLines()).toList();
        } catch (Exception exception) {
            notes.add("Loki unavailable: error logs could not be collected.");
            log.warn("Unable to collect logs for {}: {}", service, exception.getMessage());
            return List.of();
        }
    }

    private List<String> fetchTraces(String service, Instant start, Instant end, List<String> notes) {
        try {
            JsonNode response = restClient.get()
                    .uri(properties.tempoUrl() + "/api/search?tags={tags}&limit=5&start={start}&end={end}",
                            "service.name=" + service, start.getEpochSecond(), end.getEpochSecond())
                    .retrieve().body(JsonNode.class);
            var traces = new ArrayList<String>();
            response.path("traces").forEach(trace -> traces.add("traceId=%s durationMs=%s".formatted(
                    trace.path("traceID").asText(), trace.path("durationMs").asText())));
            return List.copyOf(traces);
        } catch (Exception exception) {
            notes.add("Tempo unavailable: traces could not be collected.");
            log.warn("Unable to collect traces for {}: {}", service, exception.getMessage());
            return List.of();
        }
    }

    private static String redact(String value) {
        String redacted = value.replaceAll("(?i)(password|token|secret|api[_-]?key|authorization)\\s*[=:]\\s*[^\\s,]+", "$1=[REDACTED]")
                .replaceAll("(?i)bearer\\s+[A-Za-z0-9._~+/=-]+", "Bearer [REDACTED]")
                .replaceAll("\\bsk-[A-Za-z0-9_-]{16,}\\b", "[API_KEY_REDACTED]")
                .replaceAll("[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+\\.[A-Za-z]{2,}", "[EMAIL_REDACTED]");
        return redacted.length() <= 1000 ? redacted : redacted.substring(0, 1000) + "[TRUNCATED]";
    }
}
