package com.microobserve.incident.service;

import com.microobserve.incident.model.OutboxEvent;
import com.microobserve.incident.repository.OutboxRepository;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;
import org.springframework.kafka.core.KafkaTemplate;
import tools.jackson.databind.json.JsonMapper;

import java.util.List;
import java.util.concurrent.CompletableFuture;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class OutboxPublisherTest {
    @Test
    void brokerAcknowledgementMarksPendingEventPublished() {
        var repository = mock(OutboxRepository.class);
        @SuppressWarnings("unchecked")
        KafkaTemplate<String, Object> kafka = mock(KafkaTemplate.class);
        var event = new OutboxEvent("incident-1", "incident-investigation", "{\"incidentId\":\"incident-1\"}");
        when(repository.findTop100ByPublishedAtIsNullOrderByCreatedAt()).thenReturn(List.of(event));
        when(kafka.send(eq("incident-investigation"), eq("incident-1"), any()))
                .thenReturn(CompletableFuture.completedFuture(null));
        var publisher = new OutboxPublisher(repository, kafka, JsonMapper.builder().build(), new SimpleMeterRegistry());

        publisher.publish();

        verify(repository).save(event);
    }

    @Test
    void failedKafkaPublicationLeavesEventPendingForNextScheduledAttempt() {
        var repository = mock(OutboxRepository.class);
        @SuppressWarnings("unchecked")
        KafkaTemplate<String, Object> kafka = mock(KafkaTemplate.class);
        var event = new OutboxEvent("incident-1", "incident-investigation", "{\"incidentId\":\"incident-1\"}");
        when(repository.findTop100ByPublishedAtIsNullOrderByCreatedAt()).thenReturn(List.of(event));
        when(kafka.send(eq("incident-investigation"), eq("incident-1"), any()))
                .thenReturn(CompletableFuture.failedFuture(new IllegalStateException("broker down")));
        var meterRegistry = new SimpleMeterRegistry();
        var publisher = new OutboxPublisher(repository, kafka, JsonMapper.builder().build(), meterRegistry);

        publisher.publish();

        verify(repository, never()).save(any());
        assertThat(meterRegistry.get("incident.outbox.failure").tag("topic", "incident-investigation")
                .counter().count()).isEqualTo(1.0);
    }
}
