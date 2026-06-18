package com.linkz.seatreservation.adapter.persistence;

import com.linkz.seatreservation.adapter.persistence.entity.WebhookEventEntity;
import com.linkz.seatreservation.adapter.persistence.repo.WebhookEventJpaRepository;
import com.linkz.seatreservation.business.port.external.WebhookEventRepositoryPort;
import org.springframework.stereotype.Component;
import java.time.LocalDateTime;
import java.util.Optional;

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
    public Optional<String> findStatusByEventId(String eventId) {
        return repository.findByEventId(eventId).map(WebhookEventEntity::getStatus);
    }

    @Override
    public void saveEvent(String provider, String eventId, String rawPayload, String status, String error) {
        // Find if it already exists, so we update it instead of inserting a duplicate row
        // if eventId is unique.
        Optional<WebhookEventEntity> existing = repository.findByEventId(eventId);
        if (existing.isPresent()) {
            WebhookEventEntity entity = existing.get();
            entity.setProvider(provider);
            entity.setRawPayload(rawPayload);
            entity.setStatus(status);
            entity.setProcessedAt(LocalDateTime.now());
            entity.setError(error);
            repository.save(entity);
        } else {
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
}
