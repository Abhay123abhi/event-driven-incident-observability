package com.microobserve.incident.service;

import com.microobserve.incident.api.AlertmanagerWebhook;
import com.microobserve.incident.model.IncidentRecord;
import com.microobserve.incident.model.IncidentStatus;
import com.microobserve.incident.model.InvestigationRequested;
import com.microobserve.incident.repository.IncidentRepository;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.*;

class InvestigationWorkerTest {

    @Test
    void failedEvidenceCollectionPropagatesToKafkaRetryHandler() {
        var repository = mock(IncidentRepository.class);
        var collector = mock(TelemetryEvidenceService.class);
        var investigator = mock(EvidenceIncidentInvestigator.class);
        var results = mock(InvestigationResultService.class);
        var meters = new SimpleMeterRegistry();
        var incident = new IncidentRecord("id", "fingerprint", "inventory-service",
                "HighErrorRate", "critical", Instant.now());
        var alert = new AlertmanagerWebhook.Alert("firing",
                Map.of("service", "inventory-service", "alertname", "HighErrorRate"),
                Map.of(), null, null, "fingerprint");
        when(repository.findById("id")).thenReturn(Optional.of(incident));
        when(collector.collect(alert)).thenThrow(new IllegalStateException("temporary outage"));
        var worker = new InvestigationWorker(repository, collector, investigator, results, meters);

        assertThatThrownBy(() -> worker.investigate(new InvestigationRequested("id", alert)))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("temporary outage");
        verify(results).fail("id");
        verify(investigator, never()).investigate(any());
        assertThat(meters.get("incident.investigation.failure").tag("service", "inventory-service")
                .counter().count()).isEqualTo(1.0);
    }

    @Test
    void alreadyInvestigatedIncidentsAreIdempotent() {
        var repository = mock(IncidentRepository.class);
        var collector = mock(TelemetryEvidenceService.class);
        var investigator = mock(EvidenceIncidentInvestigator.class);
        var results = mock(InvestigationResultService.class);
        var incident = new IncidentRecord("id", "fingerprint", "inventory-service",
                "HighErrorRate", "critical", Instant.now());
        incident.complete(new com.microobserve.incident.model.IncidentAnalysis(
                "Done", "critical", java.util.List.of(), java.util.List.of(), java.util.List.of()));
        when(repository.findById("id")).thenReturn(Optional.of(incident));
        var worker = new InvestigationWorker(repository, collector, investigator, results,
                new SimpleMeterRegistry());
        var alert = new AlertmanagerWebhook.Alert("firing",
                Map.of("service", "inventory-service"), Map.of(), null, null, "fingerprint");

        worker.investigate(new InvestigationRequested("id", alert));

        assertThat(incident.status()).isEqualTo(IncidentStatus.INVESTIGATED);
        verifyNoInteractions(collector, investigator, results);
        verify(repository, never()).save(any());
    }
}
