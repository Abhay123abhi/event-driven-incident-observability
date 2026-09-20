package com.microobserve.ai.model;

import java.time.Instant;
import java.util.List;

public record AiInvestigationRecord(
        String incidentId,
        String service,
        String alertName,
        String modelName,
        String probableCause,
        double confidence,
        List<String> supportingEvidence,
        List<String> counterEvidence,
        List<String> affectedServices,
        List<String> recommendations,
        List<String> missingInformation,
        List<KnowledgeDocument> retrievedContext,
        long latencyMs,
        Instant createdAt) {
}
