package com.microobserve.ai.config;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

@ConfigurationProperties("ai")
@Validated
public record AiProperties(
        boolean enabled,
        String apiKey,
        @NotBlank String baseUrl,
        @NotBlank String generationModel,
        @NotBlank String embeddingModel,
        @Min(1) @Max(20) int retrievalTopK,
        @NotBlank String investigationTopic) {
}
