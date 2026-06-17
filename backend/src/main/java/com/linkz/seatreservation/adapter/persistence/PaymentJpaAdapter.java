package com.linkz.seatreservation.adapter.persistence;

import com.linkz.seatreservation.adapter.persistence.entity.PaymentTransactionEntity;
import com.linkz.seatreservation.adapter.persistence.repo.PaymentJpaRepository;
import com.linkz.seatreservation.business.domain.model.Payment;
import com.linkz.seatreservation.business.port.external.PaymentRepositoryPort;
import org.springframework.stereotype.Component;
import java.util.Optional;
import java.util.UUID;

@Component
public class PaymentJpaAdapter implements PaymentRepositoryPort {
    private final PaymentJpaRepository repository;

    public PaymentJpaAdapter(PaymentJpaRepository repository) {
        this.repository = repository;
    }

    @Override
    public Payment save(Payment payment) {
        PaymentTransactionEntity entity = PaymentTransactionEntity.fromDomain(payment);
        return repository.save(entity).toDomain();
    }

    @Override
    public Optional<Payment> findById(UUID id) {
        return repository.findById(id).map(PaymentTransactionEntity::toDomain);
    }

    @Override
    public Optional<Payment> findByBookingId(UUID bookingId) {
        return repository.findByBookingId(bookingId).map(PaymentTransactionEntity::toDomain);
    }

    @Override
    public Optional<Payment> findByExternalPaymentId(String externalPaymentId) {
        return repository.findByExternalPaymentId(externalPaymentId).map(PaymentTransactionEntity::toDomain);
    }
}
