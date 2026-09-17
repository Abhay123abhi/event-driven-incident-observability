package com.microobserve.ai.repository;

import com.microobserve.ai.model.AiInvestigationRecord;
import com.microobserve.ai.model.KnowledgeDocument;
import com.microobserve.ai.model.RootCauseHypothesis;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import tools.jackson.databind.ObjectMapper;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;

@Repository
public class AiInvestigationRepository {

    private final JdbcClient jdbc;
    private final ObjectMapper objectMapper;

    public AiInvestigationRepository(JdbcClient jdbc, ObjectMapper objectMapper) {
        this.jdbc = jdbc;
        this.objectMapper = objectMapper;
    }

    public boolean exists(String incidentId) {
        return jdbc.sql("SELECT EXISTS(SELECT 1 FROM ai_investigations WHERE incident_id = :incidentId)")
                .param("incidentId", incidentId)
                .query(Boolean.class).single();
    }

    public void save(String incidentId, String service, String alertName, String modelName,
                     RootCauseHypothesis hypothesis, List<KnowledgeDocument> context, long latencyMs) {
        jdbc.sql("""
                INSERT INTO ai_investigations(
                    incident_id, service, alert_name, model_name, probable_cause, confidence,
                    supporting_evidence, counter_evidence, affected_services, recommendations,
                    missing_information, retrieved_context, latency_ms)
                VALUES (
                    :incidentId, :service, :alertName, :modelName, :probableCause, :confidence,
                    CAST(:supportingEvidence AS jsonb), CAST(:counterEvidence AS jsonb),
                    CAST(:affectedServices AS jsonb), CAST(:recommendations AS jsonb),
                    CAST(:missingInformation AS jsonb), CAST(:retrievedContext AS jsonb), :latencyMs)
                ON CONFLICT (incident_id) DO NOTHING
                """)
                .param("incidentId", incidentId)
                .param("service", service)
                .param("alertName", alertName)
                .param("modelName", modelName)
                .param("probableCause", hypothesis.probableCause())
                .param("confidence", hypothesis.confidence())
                .param("supportingEvidence", json(hypothesis.supportingEvidence()))
                .param("counterEvidence", json(hypothesis.counterEvidence()))
                .param("affectedServices", json(hypothesis.affectedServices()))
                .param("recommendations", json(hypothesis.recommendations()))
                .param("missingInformation", json(hypothesis.missingInformation()))
                .param("retrievedContext", json(context))
                .param("latencyMs", latencyMs)
                .update();
    }

    public Optional<AiInvestigationRecord> findByIncidentId(String incidentId) {
        return jdbc.sql("""
                SELECT incident_id, service, alert_name, model_name, probable_cause, confidence,
                       supporting_evidence::text, counter_evidence::text, affected_services::text,
                       recommendations::text, missing_information::text, retrieved_context::text,
                       latency_ms, created_at
                FROM ai_investigations
                WHERE incident_id = :incidentId
                """)
                .param("incidentId", incidentId)
                .query((rs, rowNum) -> new AiInvestigationRecord(
                        rs.getString("incident_id"),
                        rs.getString("service"),
                        rs.getString("alert_name"),
                        rs.getString("model_name"),
                        rs.getString("probable_cause"),
                        rs.getDouble("confidence"),
                        stringList(rs.getString("supporting_evidence")),
                        stringList(rs.getString("counter_evidence")),
                        stringList(rs.getString("affected_services")),
                        stringList(rs.getString("recommendations")),
                        stringList(rs.getString("missing_information")),
                        knowledgeList(rs.getString("retrieved_context")),
                        rs.getLong("latency_ms"),
                        toInstant(rs.getTimestamp("created_at"))))
                .optional();
    }

    private String json(Object value) {
        try {
            return objectMapper.writeValueAsString(value == null ? List.of() : value);
        } catch (RuntimeException exception) {
            throw new IllegalArgumentException("Unable to serialize AI investigation data", exception);
        }
    }

    private List<String> stringList(String json) {
        try {
            return Arrays.asList(objectMapper.readValue(json, String[].class));
        } catch (RuntimeException exception) {
            throw new IllegalStateException("Unable to read AI investigation JSON", exception);
        }
    }

    private List<KnowledgeDocument> knowledgeList(String json) {
        try {
            return Arrays.asList(objectMapper.readValue(json, KnowledgeDocument[].class));
        } catch (RuntimeException exception) {
            throw new IllegalStateException("Unable to read retrieved context JSON", exception);
        }
    }

    private static Instant toInstant(Timestamp timestamp) {
        return timestamp == null ? null : timestamp.toInstant();
    }
}
