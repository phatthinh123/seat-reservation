package com.linkz.seatreservation.adapter.persistence;

import com.linkz.seatreservation.adapter.persistence.entity.PaymentNotificationEntity;
import com.linkz.seatreservation.adapter.persistence.repo.PaymentNotificationJpaRepository;
import com.linkz.seatreservation.business.port.external.PaymentNotificationRepositoryPort;
import org.springframework.stereotype.Component;
import java.time.LocalDateTime;
import java.util.Optional;

@Component
public class PaymentNotificationJpaAdapter implements PaymentNotificationRepositoryPort {
    private final PaymentNotificationJpaRepository repository;

    public PaymentNotificationJpaAdapter(PaymentNotificationJpaRepository repository) {
        this.repository = repository;
    }

    @Override
    public boolean existsByEventId(String eventId) {
        return repository.existsByEventId(eventId);
    }

    @Override
    public Optional<String> findStatusByEventId(String eventId) {
        return repository.findByEventId(eventId).map(PaymentNotificationEntity::getStatus);
    }

    @Override
    public void saveEvent(String provider, String eventId, String rawPayload, String status, String error) {
        Optional<PaymentNotificationEntity> existing = repository.findByEventId(eventId);
        if (existing.isPresent()) {
            PaymentNotificationEntity entity = existing.get();
            entity.setProvider(provider);
            entity.setRawPayload(rawPayload);
            entity.setStatus(status);
            entity.setProcessedAt(LocalDateTime.now());
            entity.setError(error);
            repository.save(entity);
        } else {
            PaymentNotificationEntity entity = PaymentNotificationEntity.builder()
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
