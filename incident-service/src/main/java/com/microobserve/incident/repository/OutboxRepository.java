package com.microobserve.incident.repository;

import com.microobserve.incident.model.OutboxEvent;
import org.springframework.data.jpa.repository.JpaRepository;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

public interface OutboxRepository extends JpaRepository<OutboxEvent, UUID> {
    List<OutboxEvent> findTop100ByPublishedAtIsNullOrderByCreatedAt();
    long countByPublishedAtIsNull();
    java.util.Optional<OutboxEvent> findFirstByPublishedAtIsNullOrderByCreatedAtAsc();
    long deleteByPublishedAtBefore(Instant cutoff);
}
