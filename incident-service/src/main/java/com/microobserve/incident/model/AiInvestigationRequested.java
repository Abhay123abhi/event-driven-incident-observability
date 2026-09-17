package com.microobserve.incident.model;

import java.util.List;
import java.util.Map;

public record AiInvestigationRequested(
        String incidentId,
        String service,
        String alertName,
        String severity,
        Map<String, Double> metrics,
        List<String> recentErrors,
        List<String> traceSummaries,
        List<String> collectionNotes) {

    public static AiInvestigationRequested from(String incidentId, IncidentEvidence evidence) {
        return new AiInvestigationRequested(
                incidentId,
                evidence.service(),
                evidence.alertName(),
                evidence.severity(),
                evidence.metrics(),
                evidence.recentErrors(),
                evidence.traceSummaries(),
                evidence.collectionNotes());
    }
}
