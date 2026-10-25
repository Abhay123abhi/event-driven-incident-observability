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
        when(repository.findFirstByFingerprintOrderByDetectedAtDesc(FINGERPRINT))
                .thenReturn(Optional.empty());
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
        when(repository.findFirstByFingerprintOrderByDetectedAtDesc(FINGERPRINT))
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
        when(repository.findFirstByFingerprintOrderByDetectedAtDesc(FINGERPRINT))
                .thenReturn(Optional.empty());
        var service = new IncidentService(repository, outbox, properties(),
                new SimpleMeterRegistry(), manager());

        assertThat(service.receive(FIRING)).isNotBlank();
        verify(repository).saveAndFlush(any(IncidentRecord.class));
        verify(outbox).enqueue(anyString(), eq("incident-investigation"), any());
    }
    @Test
    void staleResolutionCannotCloseNewerEpisode() {
        var repository = mock(IncidentRepository.class);
        var outbox = mock(OutboxService.class);
        var newer = new IncidentRecord("new-id", FINGERPRINT, "inventory-service",
                "HighErrorRate", "CRITICAL", Instant.now());
        newer.setAlertStartsAt(Instant.parse("2026-10-25T12:00:00Z"));
        when(repository.findFirstByFingerprintOrderByDetectedAtDesc(FINGERPRINT))
                .thenReturn(Optional.of(newer));
        var service = new IncidentService(repository, outbox, properties(),
                new SimpleMeterRegistry(), manager());
        var stale = new AlertmanagerWebhook.Alert("resolved", FIRING.labels(),
                FIRING.annotations(), "2026-10-25T11:00:00Z", "2026-10-25T11:30:00Z", FINGERPRINT);

        assertThat(service.receive(stale)).isEqualTo("new-id");
        assertThat(newer.status()).isEqualTo(IncidentStatus.RECEIVED);
        verify(repository, never()).save(any());
        verifyNoInteractions(outbox);
    }

    @Test
    void matchedEpisodeResolvesAndSendsExactlyOneNotification() {
        var repository = mock(IncidentRepository.class);
        var outbox = mock(OutboxService.class);
        var current = new IncidentRecord("active-id", FINGERPRINT, "inventory-service",
                "HighErrorRate", "CRITICAL", Instant.now());
        current.setAlertStartsAt(Instant.parse("2026-10-25T12:00:00Z"));
        when(repository.findFirstByFingerprintOrderByDetectedAtDesc(FINGERPRINT))
                .thenReturn(Optional.of(current));
        var service = new IncidentService(repository, outbox, properties(),
                new SimpleMeterRegistry(), manager());
        var resolution = new AlertmanagerWebhook.Alert("resolved", FIRING.labels(),
                FIRING.annotations(), "2026-10-25T12:00:00Z", "2026-10-25T12:30:00Z", FINGERPRINT);

        assertThat(service.receive(resolution)).isEqualTo("active-id");
        assertThat(service.receive(resolution)).isEqualTo("active-id");
        assertThat(current.status()).isEqualTo(IncidentStatus.RESOLVED);
        verify(outbox, times(1)).enqueue(eq("active-id"), eq("incident-notification"), any());
    }

    @Test
    void oldFiringWebhookDoesNotReopenResolvedEpisode() {
        var repository = mock(IncidentRepository.class);
        var outbox = mock(OutboxService.class);
        var previous = new IncidentRecord("previous-id", FINGERPRINT, "inventory-service",
                "HighErrorRate", "CRITICAL", Instant.now());
        previous.setAlertStartsAt(Instant.parse("2026-10-25T12:00:00Z"));
        previous.resolve();
        when(repository.findByFingerprintAndStatusNot(FINGERPRINT, IncidentStatus.RESOLVED))
                .thenReturn(Optional.empty());
        when(repository.findFirstByFingerprintOrderByDetectedAtDesc(FINGERPRINT))
                .thenReturn(Optional.of(previous));
        var service = new IncidentService(repository, outbox, properties(),
                new SimpleMeterRegistry(), manager());
        var stale = new AlertmanagerWebhook.Alert("firing", FIRING.labels(),
                FIRING.annotations(), "2026-10-25T12:00:00Z", null, FINGERPRINT);

        assertThat(service.receive(stale)).isEqualTo("previous-id");
        verify(repository, never()).saveAndFlush(any());
        verifyNoInteractions(outbox);
    }

}
