package com.microobserve.incident.service;

import com.microobserve.incident.repository.OutboxRepository;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Gauge;
import java.time.Duration;
import java.time.Instant;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import tools.jackson.databind.ObjectMapper;

import java.util.concurrent.TimeUnit;

@Service
public class OutboxPublisher {

    private static final Logger log = LoggerFactory.getLogger(OutboxPublisher.class);

    private final OutboxRepository repository;
    private final KafkaTemplate<String, Object> kafkaTemplate;
    private final ObjectMapper objectMapper;
    private final MeterRegistry meters;

    public OutboxPublisher(OutboxRepository repository, KafkaTemplate<String, Object> kafkaTemplate,
                           ObjectMapper objectMapper, MeterRegistry meters) {
        this.repository = repository;
        this.kafkaTemplate = kafkaTemplate;
        this.objectMapper = objectMapper;
        this.meters = meters;
        Gauge.builder("incident.outbox.pending", repository, OutboxRepository::countByPublishedAtIsNull)
                .description("Unpublished transactional outbox events").register(meters);
        Gauge.builder("incident.outbox.oldest.pending.seconds", repository,
                repo -> repo.findFirstByPublishedAtIsNullOrderByCreatedAtAsc()
                        .map(event -> (double) Math.max(0,
                                Duration.between(event.createdAt(), Instant.now()).toSeconds()))
                        .orElse(0.0))
                .description("Age in seconds of the oldest unpublished event").register(meters);
    }

    @Scheduled(fixedDelayString = "${incident.outbox-delay:1s}")
    public void publish() {
        repository.findTop100ByPublishedAtIsNullOrderByCreatedAt().forEach(event -> {
            try {
                var payload = objectMapper.readTree(event.payload());
                kafkaTemplate.send(event.topic(), event.aggregateId(), payload)
                        .get(10, TimeUnit.SECONDS);
                event.markPublished();
                repository.save(event);
                meters.counter("incident.outbox.published", "topic", event.topic()).increment();
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
                meters.counter("incident.outbox.failure", "topic", event.topic()).increment();
                log.warn("Interrupted publishing outbox event {}", event.id(), exception);
                return;
            } catch (Exception exception) {
                meters.counter("incident.outbox.failure", "topic", event.topic()).increment();
                log.warn("Unable to publish outbox event {}: {}", event.id(), exception.getMessage());
            }
        });
    }
}
