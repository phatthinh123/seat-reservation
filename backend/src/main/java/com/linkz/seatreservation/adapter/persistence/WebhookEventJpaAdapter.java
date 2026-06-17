package com.linkz.seatreservation.adapter.persistence;

import com.linkz.seatreservation.adapter.persistence.entity.WebhookEventEntity;
import com.linkz.seatreservation.adapter.persistence.repo.WebhookEventJpaRepository;
import com.linkz.seatreservation.business.port.external.WebhookEventRepositoryPort;
import org.springframework.stereotype.Component;
import java.time.LocalDateTime;

@Component
public class WebhookEventJpaAdapter implements WebhookEventRepositoryPort {
    private final WebhookEventJpaRepository repository;

    public WebhookEventJpaAdapter(WebhookEventJpaRepository repository) {
        this.repository = repository;
    }

    @Override
    public boolean existsByEventId(String eventId) {
        return repository.existsByEventId(eventId);
    }

    @Override
    public void saveEvent(String provider, String eventId, String rawPayload, String status, String error) {
        WebhookEventEntity entity = WebhookEventEntity.builder()
            .provider(provider)
            .eventId(eventId)
            .rawPayload(rawPayload)
            .status(status)
            .processedAt(LocalDateTime.now())
            .error(error)
            .build();
        repository.save(entity);
    }
}
