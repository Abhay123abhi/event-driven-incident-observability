package com.microobserve.ai.api;

import com.microobserve.ai.model.AiInvestigationRecord;
import com.microobserve.ai.model.KnowledgeDocument;
import com.microobserve.ai.rag.KnowledgeService;
import com.microobserve.ai.repository.AiInvestigationRepository;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/ai")
public class AiInvestigationController {

    private final KnowledgeService knowledge;
    private final AiInvestigationRepository investigations;

    public AiInvestigationController(KnowledgeService knowledge, AiInvestigationRepository investigations) {
        this.knowledge = knowledge;
        this.investigations = investigations;
    }

    @PostMapping("/knowledge")
    public Map<String, Long> ingest(@Valid @RequestBody KnowledgeRequest request) {
        long id = knowledge.ingest(request.title(), request.content(), request.service(), request.documentType());
        return Map.of("id", id);
    }

    @GetMapping("/knowledge/search")
    public List<KnowledgeDocument> search(@RequestParam String query,
                                          @RequestParam(defaultValue = "") String service) {
        return knowledge.retrieve(query, service);
    }

    @GetMapping("/investigations/{incidentId}")
    public ResponseEntity<AiInvestigationRecord> investigation(@PathVariable String incidentId) {
        return investigations.findByIncidentId(incidentId)
                .map(ResponseEntity::ok)
                .orElseGet(() -> ResponseEntity.notFound().build());
    }

    public record KnowledgeRequest(
            @NotBlank String title,
            @NotBlank String content,
            String service,
            @NotBlank String documentType) {
    }
}
