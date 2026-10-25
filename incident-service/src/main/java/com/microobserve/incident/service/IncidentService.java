package com.microobserve.incident.service;

import com.microobserve.incident.api.AlertmanagerWebhook;
import com.microobserve.incident.config.IncidentProperties;
import com.microobserve.incident.model.IncidentEvent;
import com.microobserve.incident.model.IncidentRecord;
import com.microobserve.incident.model.IncidentStatus;
import com.microobserve.incident.model.InvestigationRequested;
import com.microobserve.incident.repository.IncidentRepository;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Gauge;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Instant;
import java.util.Locale;
import java.util.Objects;
import java.util.UUID;

@Service
public class IncidentService {

    private final IncidentRepository repository;
    private final OutboxService outbox;
    private final IncidentProperties properties;
    private final MeterRegistry meters;
    private final TransactionTemplate transactions;

    public IncidentService(IncidentRepository repository, OutboxService outbox,
                           IncidentProperties properties, MeterRegistry meters,
                           PlatformTransactionManager transactionManager) {
        this.repository = repository;
        this.outbox = outbox;
        this.properties = properties;
        this.meters = meters;
        this.transactions = new TransactionTemplate(transactionManager);
        Gauge.builder("incident.active", repository,
                        value -> value.countByStatusNot(IncidentStatus.RESOLVED))
                .description("Number of incidents that have not been resolved")
                .register(meters);
    }

    /**
     * The database's partial unique index protects active fingerprints from concurrent
     * insertions. A competing transaction must roll back before we can re-read the winner.
     * TransactionTemplate supplies that clean boundary without another service/container.
     */
    public String receive(AlertmanagerWebhook.Alert alert) {
        String fingerprint = fingerprint(alert);
        if ("resolved".equalsIgnoreCase(alert.status())) {
            return Objects.requireNonNull(transactions.execute(status -> resolve(fingerprint)));
        }

        IngestionResult result;
        try {
            result = Objects.requireNonNull(
                    transactions.execute(status -> receiveFiring(alert, fingerprint)));
        } catch (DataIntegrityViolationException conflict) {
            // saveAndFlush triggers the uniqueness check inside the first transaction.
            // After it rolls back, a new transaction can see the concurrently committed row.
            String existingId = transactions.execute(status -> repository
                    .findByFingerprintAndStatusNot(fingerprint, IncidentStatus.RESOLVED)
                    .map(IncidentRecord::id)
                    .orElse(null));
            if (existingId == null) {
                throw conflict; // Unrelated constraint failure: do not disguise it as a duplicate.
            }
            result = new IngestionResult(existingId, true);
        }

        meters.counter(result.duplicate() ? "incident.duplicate" : "incident.received",
                "service", alert.service()).increment();
        return result.incidentId();
    }

    private IngestionResult receiveFiring(AlertmanagerWebhook.Alert alert, String fingerprint) {
        var existing = repository.findByFingerprintAndStatusNot(fingerprint, IncidentStatus.RESOLVED);
        if (existing.isPresent()) {
            return new IngestionResult(existing.get().id(), true);
        }

        String id = UUID.randomUUID().toString();
        // Flush before inserting the outbox event so the unique-index conflict is caught
        // while the transaction is active. Incident and outbox still commit atomically.
        repository.saveAndFlush(new IncidentRecord(id, fingerprint, alert.service(), alert.alertName(),
                alert.severity().toUpperCase(Locale.ROOT), Instant.now()));
        outbox.enqueue(id, properties.investigationTopic(), new InvestigationRequested(id, alert));
        return new IngestionResult(id, false);
    }

    private record IngestionResult(String incidentId, boolean duplicate) {
    }

    @Transactional(readOnly = true)
    public Page<IncidentRecord> incidents(String scope, int page, int requestedSize) {
        int size = Math.min(requestedSize, properties.maximumPageSize());
        var pageable = PageRequest.of(page, size, Sort.by(Sort.Direction.DESC, "detectedAt"));

        return switch (scope.toLowerCase(Locale.ROOT)) {
            case "active" -> repository.findAllByStatusNotOrderByDetectedAtDesc(IncidentStatus.RESOLVED, pageable);
            case "resolved" -> repository.findAllByStatusOrderByDetectedAtDesc(IncidentStatus.RESOLVED, pageable);
            case "all" -> repository.findAll(pageable);
            default -> throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "scope must be active, resolved, or all");
        };
    }

    @Transactional(readOnly = true)
    public IncidentRecord incident(String id) {
        return repository.findById(id).orElseThrow(() ->
                new ResponseStatusException(HttpStatus.NOT_FOUND, "Incident not found"));
    }

    private String resolve(String fingerprint) {
        var incident = repository.findFirstByFingerprintOrderByDetectedAtDesc(fingerprint)
                .orElseThrow(() -> new IllegalArgumentException("No incident exists for " + fingerprint));
        if (incident.status() == IncidentStatus.RESOLVED) {
            meters.counter("incident.duplicate", "service", incident.service()).increment();
            return incident.id();
        }
        incident.resolve();
        repository.save(incident);
        outbox.enqueue(incident.id(), properties.notificationTopic(),
                toEvent(incident, properties.grafanaUrl().toString()));
        meters.counter("incident.resolved", "service", incident.service()).increment();
        return incident.id();
    }

    static IncidentEvent toEvent(IncidentRecord incident, String dashboardUrl) {
        return new IncidentEvent(incident.id(), incident.fingerprint(), incident.status().name(), incident.service(),
                incident.alertName(), incident.title(), incident.severity(),
                incident.evidence(), incident.affectedServices(), incident.recommendations(),
                dashboardUrl, incident.updatedAt());
    }

    private static String fingerprint(AlertmanagerWebhook.Alert alert) {
        return alert.fingerprint() == null || alert.fingerprint().isBlank()
                ? alert.service() + ":" + alert.alertName() : alert.fingerprint();
    }
}
