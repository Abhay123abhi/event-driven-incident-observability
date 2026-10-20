package com.microobserve.incident.service;

import com.microobserve.incident.config.IncidentProperties;
import com.microobserve.incident.model.IncidentAnalysis;
import com.microobserve.incident.model.IncidentRecord;
import com.microobserve.incident.model.IncidentStatus;
import com.microobserve.incident.repository.IncidentRepository;
import org.junit.jupiter.api.Test;

import java.net.URI;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class InvestigationResultServiceTest {
    private static final IncidentAnalysis REPORT =
            new IncidentAnalysis("Evidence report", "critical", List.of("metric=2"),
                    List.of("order-service"), List.of("Inspect traces"));

    private static IncidentProperties properties() {
        var uri = URI.create("http://localhost:9000");
        return new IncidentProperties(uri, uri, uri, uri, "incident-investigation",
                "incident-notification", 10, 100, 30, 7);
    }

    @Test
    void lateCompletionKeepsResolutionAndDoesNotPublishDuplicateRecovery() {
        var repository = mock(IncidentRepository.class);
        var outbox = mock(OutboxService.class);
        var incident = new IncidentRecord("id", "fingerprint", "order-service",
                "HighErrorRate", "CRITICAL", Instant.now());
        incident.resolve();
        when(repository.findById("id")).thenReturn(Optional.of(incident));

        boolean completed = new InvestigationResultService(repository, outbox, properties())
                .complete("id", REPORT);

        assertThat(completed).isTrue();
        assertThat(incident.status()).isEqualTo(IncidentStatus.RESOLVED);
        assertThat(incident.investigatedAt()).isNotNull();
        verify(repository).save(incident);
        verifyNoInteractions(outbox);
    }

    @Test
    void completedInvestigationEnqueuesNotificationOnceOnDuplicateDelivery() {
        var repository = mock(IncidentRepository.class);
        var outbox = mock(OutboxService.class);
        var incident = new IncidentRecord("id", "fingerprint", "order-service",
                "HighErrorRate", "CRITICAL", Instant.now());
        when(repository.findById("id")).thenReturn(Optional.of(incident));
        var service = new InvestigationResultService(repository, outbox, properties());

        assertThat(service.complete("id", REPORT)).isTrue();
        assertThat(service.complete("id", REPORT)).isFalse();
        verify(repository).save(incident);
        verify(outbox, times(1)).enqueue(eq("id"), eq("incident-notification"), any());
    }

    @Test
    void failedInvestigationDoesNotResolveIncident() {
        var repository = mock(IncidentRepository.class);
        var outbox = mock(OutboxService.class);
        var incident = new IncidentRecord("id", "fingerprint", "order-service",
                "HighErrorRate", "CRITICAL", Instant.now());
        when(repository.findById("id")).thenReturn(Optional.of(incident));

        new InvestigationResultService(repository, outbox, properties()).fail("id");

        assertThat(incident.status()).isEqualTo(IncidentStatus.INVESTIGATION_FAILED);
        verify(repository).save(incident);
        verifyNoInteractions(outbox);
    }
}
