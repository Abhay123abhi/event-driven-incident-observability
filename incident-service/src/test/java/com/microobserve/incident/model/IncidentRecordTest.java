package com.microobserve.incident.model;

import org.junit.jupiter.api.Test;
import java.time.Instant;
import java.util.List;
import static org.assertj.core.api.Assertions.assertThat;

class IncidentRecordTest {
    private IncidentRecord incident() {
        return new IncidentRecord("id", "fingerprint", "order-service", "HighErrorRate", "CRITICAL", Instant.now());
    }

    private IncidentAnalysis report(String title) {
        return new IncidentAnalysis(title, "critical", List.of("error_requests_per_second=2"),
                List.of("order-service"), List.of("Inspect logs"));
    }

    @Test
    void lateWorkerCannotReopenResolvedIncident() {
        var incident = incident();
        incident.startInvestigation();
        incident.resolve();
        var resolvedAt = incident.resolvedAt();
        incident.complete(report("Late result"));
        incident.failInvestigation();
        incident.startInvestigation();
        incident.resolve();
        assertThat(incident.status()).isEqualTo(IncidentStatus.RESOLVED);
        assertThat(incident.investigationFinished()).isTrue();
        assertThat(incident.resolvedAt()).isEqualTo(resolvedAt);
        assertThat(incident.title()).isEqualTo("Late result");
        assertThat(incident.investigatedAt()).isNotNull();
        assertThat(incident.evidence()).contains("error_requests_per_second=2");
    }

    @Test
    void repeatedWorkDoesNotReplaceCompletedReport() {
        var incident = incident();
        incident.startInvestigation();
        incident.complete(report("First report"));
        incident.startInvestigation();
        incident.complete(report("Duplicate report"));
        incident.failInvestigation();
        assertThat(incident.status()).isEqualTo(IncidentStatus.INVESTIGATED);
        assertThat(incident.title()).isEqualTo("First report");
        incident.resolve();
        assertThat(incident.status()).isEqualTo(IncidentStatus.RESOLVED);
    }
}
