package com.microobserve.incident.service;

import com.microobserve.incident.api.AlertmanagerWebhook;
import com.microobserve.incident.config.IncidentProperties;
import com.microobserve.incident.model.IncidentRecord;
import com.microobserve.incident.model.IncidentStatus;
import com.microobserve.incident.repository.IncidentRepository;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.SimpleTransactionStatus;
import org.springframework.transaction.TransactionDefinition;

import java.net.URI;
import java.time.Instant;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class IncidentServiceDeduplicationTest {

    private static final String FINGERPRINT = "alert-123";

    private static final AlertmanagerWebhook.Alert FIRING = new AlertmanagerWebhook.Alert(
            "firing",
            Map.of("service", "inventory-service", "alertname", "HighErrorRate", "severity", "critical"),
            Map.of(), Instant.now().toString(), null, FINGERPRINT);

    private static IncidentProperties properties() {
        URI uri = URI.create("http://localhost:9000");
        return new IncidentProperties(uri, uri, uri, uri,
                "incident-investigation", "incident-notification", 10, 100, 30, 7);
    }

    private static PlatformTransactionManager manager() {
        PlatformTransactionManager manager = mock(PlatformTransactionManager.class);
        when(manager.getTransaction(any(TransactionDefinition.class)))
                .thenAnswer(invocation -> new SimpleTransactionStatus());
        return manager;
    }

    @Test
    void existingActiveFingerprintReturnsSameIncidentWithoutEnqueueing() {
        var repository = mock(IncidentRepository.class);
        var outbox = mock(OutboxService.class);
        var active = new IncidentRecord("existing-id", FINGERPRINT,
                "inventory-service", "HighErrorRate", "CRITICAL", Instant.now());
        when(repository.findByFingerprintAndStatusNot(FINGERPRINT, IncidentStatus.RESOLVED))
                .thenReturn(Optional.of(active));
        var meters = new SimpleMeterRegistry();
        var service = new IncidentService(repository, outbox, properties(), meters, manager());

        assertThat(service.receive(FIRING)).isEqualTo("existing-id");
        verify(repository, never()).saveAndFlush(any());
        verifyNoInteractions(outbox);
        assertThat(meters.get("incident.duplicate").tag("service", "inventory-service")
                .counter().count()).isEqualTo(1.0);
    }

    @Test
    void uniqueConstraintRaceReturnsWinnerAndDoesNotPublishAnotherOutboxEvent() {
        var repository = mock(IncidentRepository.class);
        var outbox = mock(OutboxService.class);
        var winner = new IncidentRecord("winner-id", FINGERPRINT,
                "inventory-service", "HighErrorRate", "CRITICAL", Instant.now());
        when(repository.findByFingerprintAndStatusNot(FINGERPRINT, IncidentStatus.RESOLVED))
                .thenReturn(Optional.empty(), Optional.of(winner));
        when(repository.saveAndFlush(any(IncidentRecord.class)))
                .thenThrow(new DataIntegrityViolationException("unique active fingerprint"));
        var meters = new SimpleMeterRegistry();
        var service = new IncidentService(repository, outbox, properties(), meters, manager());

        assertThat(service.receive(FIRING)).isEqualTo("winner-id");
        verify(repository, times(1)).saveAndFlush(any(IncidentRecord.class));
        verifyNoInteractions(outbox);
        assertThat(meters.get("incident.duplicate").tag("service", "inventory-service")
                .counter().count()).isEqualTo(1.0);
    }

    @Test
    void unrelatedIntegrityFailureIsNotSilentlySwallowed() {
        var repository = mock(IncidentRepository.class);
        var outbox = mock(OutboxService.class);
        when(repository.findByFingerprintAndStatusNot(FINGERPRINT, IncidentStatus.RESOLVED))
                .thenReturn(Optional.empty());
        when(repository.saveAndFlush(any(IncidentRecord.class)))
                .thenThrow(new DataIntegrityViolationException("different constraint"));
        var service = new IncidentService(repository, outbox, properties(),
                new SimpleMeterRegistry(), manager());

        assertThatThrownBy(() -> service.receive(FIRING))
                .isInstanceOf(DataIntegrityViolationException.class);
        verifyNoInteractions(outbox);
    }

    @Test
    void freshIncidentCreatesExactlyOneOutboxMessage() {
        var repository = mock(IncidentRepository.class);
        var outbox = mock(OutboxService.class);
        when(repository.findByFingerprintAndStatusNot(FINGERPRINT, IncidentStatus.RESOLVED))
                .thenReturn(Optional.empty());
        var service = new IncidentService(repository, outbox, properties(),
                new SimpleMeterRegistry(), manager());

        assertThat(service.receive(FIRING)).isNotBlank();
        verify(repository).saveAndFlush(any(IncidentRecord.class));
        verify(outbox).enqueue(anyString(), eq("incident-investigation"), any());
    }
}
