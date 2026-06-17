package com.linkz.seatreservation.business.port.external;

import com.linkz.seatreservation.business.domain.model.Payment;
import java.util.Optional;
import java.util.UUID;

public interface PaymentRepositoryPort {
    Payment save(Payment payment);
    Optional<Payment> findById(UUID id);
    Optional<Payment> findByBookingId(UUID bookingId);
    Optional<Payment> findByExternalPaymentId(String externalPaymentId);
}
