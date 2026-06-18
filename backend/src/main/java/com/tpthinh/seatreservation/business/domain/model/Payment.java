package com.tpthinh.seatreservation.business.domain.model;

import com.tpthinh.seatreservation.business.domain.enums.PaymentStatus;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.UUID;

public record Payment(
    UUID id,
    UUID bookingId,
    String externalPaymentId,
    BigDecimal amount,
    PaymentStatus status,
    String rawPayload,
    LocalDateTime createdAt,
    LocalDateTime updatedAt
) {}
