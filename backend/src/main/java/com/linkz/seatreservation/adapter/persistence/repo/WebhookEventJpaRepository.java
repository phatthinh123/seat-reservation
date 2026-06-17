package com.linkz.seatreservation.adapter.persistence.repo;

import com.linkz.seatreservation.adapter.persistence.entity.WebhookEventEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.UUID;

public interface WebhookEventJpaRepository extends JpaRepository<WebhookEventEntity, UUID> {
    boolean existsByEventId(String eventId);
}
