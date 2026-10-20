package com.microobserve.incident.service;

import com.microobserve.incident.api.AlertmanagerWebhook;
import com.microobserve.incident.config.IncidentProperties;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;
import org.springframework.web.client.RestClient;

import java.net.InetSocketAddress;
import java.net.URI;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Map;
import static org.assertj.core.api.Assertions.assertThat;

class TelemetryEvidenceServiceTest {
    @Test
    void queriesOneWindowAndReportsUnavailableSources() throws Exception {
        var requests = new ArrayList<String>();
        var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            requests.add(URLDecoder.decode(exchange.getRequestURI().toString(), StandardCharsets.UTF_8));
            String path = exchange.getRequestURI().getPath();
            String body = path.equals("/api/v1/query")
                    ? "{\"status\":\"success\",\"data\":{\"result\":[{\"value\":[1,\"NaN\"]}]}}"
                    : "{}";
            byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(path.startsWith("/loki/") ? 503 : 200, bytes.length);
            try (var output = exchange.getResponseBody()) { output.write(bytes); }
        });
        server.start();
        try {
            var uri = URI.create("http://127.0.0.1:" + server.getAddress().getPort());
            var properties = new IncidentProperties(uri, uri, uri, uri,
                    "investigation", "notification", 10, 100, 30, 7);
            var service = new TelemetryEvidenceService(RestClient.builder(), properties);
            var alert = new AlertmanagerWebhook.Alert("firing",
                    Map.of("service", "inventory-service", "alertname", "HighResponseLatency"),
                    Map.of(), null, null, "test");
            var evidence = service.collect(alert);
            assertThat(evidence.metrics()).isEmpty();
            assertThat(evidence.collectionNotes()).anyMatch(note -> note.startsWith("Loki unavailable"));
            assertThat(requests).hasSize(6);
            assertThat(requests.subList(0, 4)).allMatch(request -> request.contains("&time="));
            assertThat(requests.get(4)).contains("&start=", "&end=", "&limit=10");
            assertThat(requests.get(5)).contains("tags=service.name=inventory-service", "&start=", "&end=");
        } finally {
            server.stop(0);
        }
    }
}
