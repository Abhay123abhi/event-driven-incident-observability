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
    void delayedWorkerPersistsEvidenceWithoutReopeningResolvedIncident() {
        var incident = incident();
        incident.startInvestigation();
        incident.resolve();
        var resolution = incident.resolvedAt();
        incident.complete(report("Late report"));
        incident.failInvestigation();
        incident.startInvestigation();
        assertThat(incident.status()).isEqualTo(IncidentStatus.RESOLVED);
        assertThat(incident.resolvedAt()).isEqualTo(resolution);
        assertThat(incident.investigatedAt()).isNotNull();
        assertThat(incident.title()).isEqualTo("Late report");
        assertThat(incident.evidence()).contains("error_requests_per_second=2");
    }

    @Test
    void duplicateDeliveryNeverOverwritesCompletedEvidence() {
        var incident = incident();
        incident.complete(report("Original"));
        var investigatedAt = incident.investigatedAt();
        incident.complete(report("Duplicate"));
        incident.failInvestigation();
        assertThat(incident.title()).isEqualTo("Original");
        assertThat(incident.investigatedAt()).isEqualTo(investigatedAt);
        assertThat(incident.status()).isEqualTo(IncidentStatus.INVESTIGATED);
    }

    @Test
    void failedAttemptCanBeReprocessedBeforeResolution() {
        var incident = incident();
        incident.startInvestigation();
        incident.failInvestigation();
        assertThat(incident.status()).isEqualTo(IncidentStatus.INVESTIGATION_FAILED);
        incident.startInvestigation();
        incident.complete(report("Recovered"));
        assertThat(incident.status()).isEqualTo(IncidentStatus.INVESTIGATED);
        assertThat(incident.investigationFinished()).isTrue();
    }
}
