package com.tpthinh.seatreservation.web.dto;

import com.tpthinh.seatreservation.business.domain.model.Booking;
import java.time.LocalDateTime;
import java.util.UUID;

public record BookingResponse(
    UUID bookingId,
    UUID seatId,
    String seatLabel,
    String status,
    LocalDateTime holdExpiresAt,
    String idempotencyKey
) {
    public static BookingResponse from(Booking booking, String seatLabel) {
        if (booking == null) return null;
        return new BookingResponse(
            booking.id(),
            booking.seatId(),
            seatLabel,
            booking.status().name(),
            booking.holdExpiresAt(),
            booking.idempotencyKey()
        );
    }
}
