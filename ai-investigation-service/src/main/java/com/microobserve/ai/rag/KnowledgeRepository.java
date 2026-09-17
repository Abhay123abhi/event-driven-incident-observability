package com.microobserve.ai.rag;

import com.microobserve.ai.model.KnowledgeDocument;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public class KnowledgeRepository {

    private final JdbcClient jdbc;

    public KnowledgeRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public long save(String title, String content, String service, String documentType, List<Double> embedding) {
        return jdbc.sql("""
                INSERT INTO knowledge_documents(title, content, service, document_type, embedding)
                VALUES (:title, :content, :service, :documentType, CAST(:embedding AS vector))
                RETURNING id
                """)
                .param("title", title)
                .param("content", content)
                .param("service", service)
                .param("documentType", documentType)
                .param("embedding", vectorLiteral(embedding))
                .query(Long.class).single();
    }

    public List<KnowledgeDocument> search(List<Double> embedding, String service, int topK) {
        return jdbc.sql("""
                SELECT id, title, content, service, document_type,
                       1 - (embedding <=> CAST(:embedding AS vector)) AS similarity
                FROM knowledge_documents
                WHERE service IS NULL OR service = '' OR service = :service
                ORDER BY embedding <=> CAST(:embedding AS vector)
                LIMIT :topK
                """)
                .param("embedding", vectorLiteral(embedding))
                .param("service", service == null ? "" : service)
                .param("topK", topK)
                .query((rs, rowNum) -> new KnowledgeDocument(
                        rs.getLong("id"),
                        rs.getString("title"),
                        rs.getString("content"),
                        rs.getString("service"),
                        rs.getString("document_type"),
                        rs.getDouble("similarity")))
                .list();
    }

    private static String vectorLiteral(List<Double> embedding) {
        return embedding.stream().map(String::valueOf).collect(java.util.stream.Collectors.joining(",", "[", "]"));
    }
}
