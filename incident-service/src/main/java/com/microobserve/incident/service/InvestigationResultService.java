package com.microobserve.incident.service;

import com.microobserve.incident.config.IncidentProperties;
import com.microobserve.incident.model.IncidentAnalysis;
import com.microobserve.incident.repository.IncidentRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class InvestigationResultService {

    private final IncidentRepository repository;
    private final OutboxService outbox;
    private final IncidentProperties properties;

    public InvestigationResultService(IncidentRepository repository, OutboxService outbox,
                                      IncidentProperties properties) {
        this.repository = repository;
        this.outbox = outbox;
        this.properties = properties;
    }

    @Transactional
    public boolean complete(String incidentId, IncidentAnalysis analysis) {
        var incident = repository.findById(incidentId)
                .orElseThrow(() -> new IllegalArgumentException("Unknown incident " + incidentId));
        if (incident.investigationFinished()) return false;
        incident.complete(analysis);
        repository.save(incident);
        outbox.enqueue(incident.id(), properties.notificationTopic(),
                IncidentService.toEvent(incident, properties.grafanaUrl().toString()));
        return true;
    }

    @Transactional
    public void fail(String incidentId) {
        var incident = repository.findById(incidentId)
                .orElseThrow(() -> new IllegalArgumentException("Unknown incident " + incidentId));
        incident.failInvestigation();
        repository.save(incident);
    }
}
