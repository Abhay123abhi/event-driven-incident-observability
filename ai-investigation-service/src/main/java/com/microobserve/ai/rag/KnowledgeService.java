package com.microobserve.ai.rag;

import com.microobserve.ai.config.AiProperties;
import com.microobserve.ai.llm.GeminiClient;
import com.microobserve.ai.model.KnowledgeDocument;
import org.springframework.stereotype.Service;

import java.util.List;

@Service
public class KnowledgeService {

    private final GeminiClient gemini;
    private final KnowledgeRepository repository;
    private final AiProperties properties;

    public KnowledgeService(GeminiClient gemini, KnowledgeRepository repository, AiProperties properties) {
        this.gemini = gemini;
        this.repository = repository;
        this.properties = properties;
    }

    public long ingest(String title, String content, String service, String documentType) {
        var embedding = gemini.embed("Document title: %s\n%s".formatted(title, content));
        return repository.save(title, content, service, documentType, embedding);
    }

    public List<KnowledgeDocument> retrieve(String query, String service) {
        return repository.search(gemini.embed(query), service, properties.retrievalTopK());
    }
}
