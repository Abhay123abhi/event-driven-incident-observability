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
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.Locale;
import java.util.UUID;

@Service
public class IncidentService {

    private final IncidentRepository repository;
    private final OutboxService outbox;
    private final IncidentProperties properties;
    private final MeterRegistry meters;

    public IncidentService(IncidentRepository repository, OutboxService outbox,
                           IncidentProperties properties, MeterRegistry meters) {
        this.repository = repository;
        this.outbox = outbox;
        this.properties = properties;
        this.meters = meters;
        Gauge.builder("incident.active", repository,
                        value -> value.countByStatusNot(IncidentStatus.RESOLVED))
                .description("Number of incidents that have not been resolved")
                .register(meters);
    }

    @Transactional
    public String receive(AlertmanagerWebhook.Alert alert) {
        String fingerprint = fingerprint(alert);
        if ("resolved".equalsIgnoreCase(alert.status())) {
            return resolve(fingerprint);
        }

        var existing = repository.findByFingerprintAndStatusNot(fingerprint, IncidentStatus.RESOLVED);
        if (existing.isPresent()) {
            meters.counter("incident.duplicate", "service", alert.service()).increment();
            return existing.get().id();
        }

        String id = UUID.randomUUID().toString();
        repository.save(new IncidentRecord(id, fingerprint, alert.service(), alert.alertName(),
                alert.severity().toUpperCase(), Instant.now()));
        outbox.enqueue(id, properties.investigationTopic(), new InvestigationRequested(id, alert));
        meters.counter("incident.received", "service", alert.service()).increment();
        return id;
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
