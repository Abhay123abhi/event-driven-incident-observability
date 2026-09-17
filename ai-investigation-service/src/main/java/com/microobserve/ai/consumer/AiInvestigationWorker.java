package com.microobserve.ai.consumer;

import com.microobserve.ai.model.AiInvestigationRequested;
import com.microobserve.ai.service.AiInvestigationService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Service;

@Service
@ConditionalOnProperty(name = "ai.enabled", havingValue = "true")
public class AiInvestigationWorker {

    private static final Logger log = LoggerFactory.getLogger(AiInvestigationWorker.class);
    private final AiInvestigationService service;

    public AiInvestigationWorker(AiInvestigationService service) {
        this.service = service;
    }

    @KafkaListener(topics = "${ai.investigation-topic:incident-ai-analysis}",
            groupId = "ai-investigation-worker")
    public void consume(AiInvestigationRequested request) {
        try {
            service.investigate(request);
            log.info("AI investigation completed for {}", request.incidentId());
        } catch (RuntimeException exception) {
            log.error("AI investigation failed for {}", request.incidentId(), exception);
            throw exception;
        }
    }
}
