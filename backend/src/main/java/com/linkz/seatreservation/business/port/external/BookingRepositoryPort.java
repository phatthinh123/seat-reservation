package com.tpthinh.seatreservation.business.port.external;

import com.tpthinh.seatreservation.business.domain.model.Booking;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface BookingRepositoryPort {
    Booking save(Booking booking);
    Optional<Booking> findById(UUID id);
    Optional<Booking> findByIdForUpdate(UUID id);
    Optional<Booking> findByIdempotencyKey(String key);
    Optional<Booking> findByExternalPaymentIdForUpdate(String externalPaymentId);
    List<Booking> findExpiredPending(LocalDateTime now);
    List<Booking> findExpiredPendingForUpdate(LocalDateTime now);
    List<Booking> findAllPending();
}
