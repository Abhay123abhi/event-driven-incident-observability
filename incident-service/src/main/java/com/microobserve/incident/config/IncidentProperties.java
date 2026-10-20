package com.microobserve.incident.config;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

import java.net.URI;

@ConfigurationProperties("incident")
@Validated
public record IncidentProperties(
        @NotNull URI prometheusUrl,
        @NotNull URI lokiUrl,
        @NotNull URI tempoUrl,
        @NotNull URI grafanaUrl,
        @NotBlank String investigationTopic,
        @NotBlank String notificationTopic,
        @Min(1) @Max(50) int maximumLogLines,
        @Min(1) @Max(100) int maximumPageSize,
        @Min(1) @Max(3_650) int resolvedRetentionDays,
        @Min(1) @Max(365) int outboxRetentionDays) {
}
