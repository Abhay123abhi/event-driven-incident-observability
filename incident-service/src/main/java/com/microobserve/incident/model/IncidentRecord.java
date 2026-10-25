package com.microobserve.incident.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.Instant;
import java.util.List;

@Entity
@Table(name = "incidents")
public class IncidentRecord {

    @Id
    private String id;

    @Column(nullable = false)
    private String fingerprint;

    @Column(nullable = false)
    private String service;

    @Column(nullable = false)
    private String alertName;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private IncidentStatus status;

    private String severity;
    private String title;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(columnDefinition = "jsonb")
    private List<String> evidence = List.of();

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(columnDefinition = "jsonb")
    private List<String> affectedServices = List.of();

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(columnDefinition = "jsonb")
    private List<String> recommendations = List.of();

    @Column(nullable = false, updatable = false)
    private Instant detectedAt;
    private Instant updatedAt;
    private Instant resolvedAt;
    private Instant investigatedAt;
    private Instant alertStartsAt;

    @Version
    private long version;

    protected IncidentRecord() {
    }

    public IncidentRecord(String id, String fingerprint, String service, String alertName,
                          String severity, Instant detectedAt) {
        this.id = id;
        this.fingerprint = fingerprint;
        this.service = service;
        this.alertName = alertName;
        this.severity = severity;
        this.title = alertName + " detected in " + service;
        this.status = IncidentStatus.RECEIVED;
        this.detectedAt = detectedAt;
        this.updatedAt = detectedAt;
    }

    public void setAlertStartsAt(Instant alertStartsAt) {
        this.alertStartsAt = alertStartsAt;
    }

    public boolean investigationFinished() {
        return status == IncidentStatus.INVESTIGATED || investigatedAt != null;
    }

    public void startInvestigation() {
        if (investigationFinished()) return;
        if (status != IncidentStatus.RESOLVED) {
            status = IncidentStatus.INVESTIGATING;
            updatedAt = Instant.now();
        }
    }

    public void complete(IncidentAnalysis analysis) {
        if (investigationFinished()) return;
        title = analysis.title();
        severity = analysis.severity();
        evidence = List.copyOf(analysis.evidence());
        affectedServices = List.copyOf(analysis.affectedServices());
        recommendations = List.copyOf(analysis.recommendations());
        investigatedAt = Instant.now();
        if (status != IncidentStatus.RESOLVED) status = IncidentStatus.INVESTIGATED;
        updatedAt = investigatedAt;
    }

    public void failInvestigation() {
        if (investigationFinished()) return;
        if (status != IncidentStatus.RESOLVED) {
            status = IncidentStatus.INVESTIGATION_FAILED;
            updatedAt = Instant.now();
        }
    }

    public void resolve() {
        if (status == IncidentStatus.RESOLVED) return;
        status = IncidentStatus.RESOLVED;
        resolvedAt = Instant.now();
        updatedAt = resolvedAt;
    }

    public String id() { return id; }
    public String fingerprint() { return fingerprint; }
    public String service() { return service; }
    public String alertName() { return alertName; }
    public IncidentStatus status() { return status; }
    public String severity() { return severity; }
    public String title() { return title; }
    public List<String> evidence() { return evidence; }
    public List<String> affectedServices() { return affectedServices; }
    public List<String> recommendations() { return recommendations; }
    public Instant detectedAt() { return detectedAt; }
    public Instant updatedAt() { return updatedAt; }
    public Instant resolvedAt() { return resolvedAt; }
    public Instant investigatedAt() { return investigatedAt; }
    public Instant alertStartsAt() { return alertStartsAt; }
}
