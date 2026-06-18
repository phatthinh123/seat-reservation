package com.tpthinh.seatreservation.adapter.persistence.repo;

import com.tpthinh.seatreservation.adapter.persistence.entity.PaymentNotificationEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.Optional;
import java.util.UUID;

public interface PaymentNotificationJpaRepository extends JpaRepository<PaymentNotificationEntity, UUID> {
    boolean existsByEventId(String eventId);
    Optional<PaymentNotificationEntity> findByEventId(String eventId);
}
