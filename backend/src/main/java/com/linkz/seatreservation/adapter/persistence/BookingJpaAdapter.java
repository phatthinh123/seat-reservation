package com.linkz.seatreservation.adapter.persistence;

import com.linkz.seatreservation.adapter.persistence.entity.BookingEntity;
import com.linkz.seatreservation.adapter.persistence.mapper.EntityMapper;
import com.linkz.seatreservation.adapter.persistence.repo.BookingJpaRepository;
import com.linkz.seatreservation.business.domain.model.Booking;
import com.linkz.seatreservation.business.port.external.BookingRepositoryPort;
import org.springframework.stereotype.Component;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Component
public class BookingJpaAdapter implements BookingRepositoryPort {
    private final BookingJpaRepository repository;
    private final EntityMapper mapper;

    public BookingJpaAdapter(BookingJpaRepository repository, EntityMapper mapper) {
        this.repository = repository;
        this.mapper = mapper;
    }

    @Override
    public Booking save(Booking booking) {
        BookingEntity entity = mapper.fromDomain(booking);
        return mapper.toDomain(repository.save(entity));
    }

    @Override
    public Optional<Booking> findById(UUID id) {
        return repository.findById(id).map(mapper::toDomain);
    }

    @Override
    public Optional<Booking> findByIdForUpdate(UUID id) {
        return repository.findByIdForUpdate(id).map(mapper::toDomain);
    }

    @Override
    public Optional<Booking> findByIdempotencyKey(String key) {
        return repository.findByIdempotencyKey(key).map(mapper::toDomain);
    }

    @Override
    public Optional<Booking> findByExternalPaymentIdForUpdate(String externalPaymentId) {
        return repository.findByExternalPaymentIdForUpdate(externalPaymentId).map(mapper::toDomain);
    }

    @Override
    public List<Booking> findExpiredPending(LocalDateTime now) {
        return repository.findExpiredPending(now).stream().map(mapper::toDomain).toList();
    }

    @Override
    public List<Booking> findExpiredPendingForUpdate(LocalDateTime now) {
        return repository.findExpiredPendingForUpdate(now).stream().map(mapper::toDomain).toList();
    }

    @Override
    public List<Booking> findAllPending() {
        return repository.findAllPending().stream().map(mapper::toDomain).toList();
    }
}
