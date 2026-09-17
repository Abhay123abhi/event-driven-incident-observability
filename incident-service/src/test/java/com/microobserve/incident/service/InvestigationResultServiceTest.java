package com.microobserve.incident.service;

import com.microobserve.incident.config.IncidentProperties;
import com.microobserve.incident.model.IncidentAnalysis;
import com.microobserve.incident.model.IncidentEvidence;
import com.microobserve.incident.model.IncidentRecord;
import com.microobserve.incident.repository.IncidentRepository;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.mockito.Mockito.*;

class InvestigationResultServiceTest {
    @Test
    void lateCompletionDoesNotQueueAnotherNotification() {
        var repository = mock(IncidentRepository.class);
        var outbox = mock(OutboxService.class);
        var incident = new IncidentRecord("id", "fingerprint", "order-service", "HighErrorRate", "CRITICAL", Instant.now());
        incident.resolve();
        when(repository.findById("id")).thenReturn(Optional.of(incident));
        var service = new InvestigationResultService(repository, outbox, mock(IncidentProperties.class));
        var evidence = new IncidentEvidence("order-service", "HighErrorRate", "CRITICAL",
                Map.of(), List.of(), List.of(), List.of());
        service.complete("id", new IncidentAnalysis("Late report", "critical", List.of(), List.of(), List.of()), evidence);
        verify(repository, never()).save(any());
        verifyNoInteractions(outbox);
    }
}
