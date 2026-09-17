package com.microobserve.ai.model;

public record KnowledgeDocument(
        Long id,
        String title,
        String content,
        String service,
        String documentType,
        double similarity) {
}
