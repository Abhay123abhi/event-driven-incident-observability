package com.microobserve.incident.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "outbox_events")
public class OutboxEvent {

    @Id
    private UUID id;

    @Column(nullable = false)
    private String aggregateId;

    @Column(nullable = false)
    private String topic;

    @Column(nullable = false, columnDefinition = "text")
    private String payload;

    @Column(nullable = false, updatable = false)
    private Instant createdAt;

    private Instant publishedAt;

    protected OutboxEvent() {
    }

    public OutboxEvent(String aggregateId, String topic, String payload) {
        this.id = UUID.randomUUID();
        this.aggregateId = aggregateId;
        this.topic = topic;
        this.payload = payload;
        this.createdAt = Instant.now();
    }

    public void markPublished() {
        publishedAt = Instant.now();
    }

    public UUID id() { return id; }
    public String aggregateId() { return aggregateId; }
    public String topic() { return topic; }
    public String payload() { return payload; }
    public Instant createdAt() { return createdAt; }
}
