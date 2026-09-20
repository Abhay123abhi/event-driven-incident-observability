package com.microobserve.ai.model;

import java.util.List;

public record RootCauseHypothesis(
        String probableCause,
        double confidence,
        List<String> supportingEvidence,
        List<String> counterEvidence,
        List<String> affectedServices,
        List<String> recommendations,
        List<String> missingInformation) {
}
