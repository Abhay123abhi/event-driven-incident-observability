package com.microobserve.ai.model;

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
}
