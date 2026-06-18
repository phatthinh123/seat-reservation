package com.tpthinh.seatreservation.adapter.persistence;

import com.tpthinh.seatreservation.adapter.persistence.entity.PaymentTransactionEntity;
import com.tpthinh.seatreservation.adapter.persistence.mapper.EntityMapper;
import com.tpthinh.seatreservation.adapter.persistence.repo.PaymentJpaRepository;
import com.tpthinh.seatreservation.business.domain.model.Payment;
import com.tpthinh.seatreservation.business.port.external.PaymentRepositoryPort;
import org.springframework.stereotype.Component;
import java.util.Optional;
import java.util.UUID;

@Component
public class PaymentJpaAdapter implements PaymentRepositoryPort {
    private final PaymentJpaRepository repository;
    private final EntityMapper mapper;

    public PaymentJpaAdapter(PaymentJpaRepository repository, EntityMapper mapper) {
        this.repository = repository;
        this.mapper = mapper;
    }

    @Override
    public Payment save(Payment payment) {
        PaymentTransactionEntity entity = mapper.fromDomain(payment);
        return mapper.toDomain(repository.save(entity));
    }

    @Override
    public Optional<Payment> findById(UUID id) {
        return repository.findById(id).map(mapper::toDomain);
    }

    @Override
    public Optional<Payment> findByBookingId(UUID bookingId) {
        return repository.findByBookingId(bookingId).map(mapper::toDomain);
    }

    @Override
    public Optional<Payment> findByExternalPaymentId(String externalPaymentId) {
        return repository.findByExternalPaymentId(externalPaymentId).map(mapper::toDomain);
    }
}
