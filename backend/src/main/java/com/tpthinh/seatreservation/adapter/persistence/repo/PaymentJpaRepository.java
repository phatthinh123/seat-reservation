package com.tpthinh.seatreservation.adapter.persistence.repo;

import com.tpthinh.seatreservation.adapter.persistence.entity.PaymentTransactionEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.Optional;
import java.util.UUID;

public interface PaymentJpaRepository extends JpaRepository<PaymentTransactionEntity, UUID> {
    Optional<PaymentTransactionEntity> findByBookingId(UUID bookingId);
    Optional<PaymentTransactionEntity> findByExternalPaymentId(String externalPaymentId);
}
