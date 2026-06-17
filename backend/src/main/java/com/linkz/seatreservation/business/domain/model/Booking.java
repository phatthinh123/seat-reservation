package com.linkz.seatreservation.business.domain.model;

import com.linkz.seatreservation.business.domain.enums.BookingStatus;
import java.time.LocalDateTime;
import java.util.UUID;

public record Booking(
    UUID id,
    String userId,
    UUID seatId,
    BookingStatus status,
    String idempotencyKey,
    LocalDateTime holdExpiresAt,
    LocalDateTime createdAt,
    LocalDateTime updatedAt
) {}
