package com.linkz.seatreservation.web.dto;

import com.linkz.seatreservation.business.domain.model.Booking;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.UUID;

public record AdminBookingDto(
    UUID id,
    String userId,
    UUID seatId,
    String seatLabel,
    String status,
    LocalDateTime holdExpiresAt,
    LocalDateTime createdAt,
    long ageSeconds
) {
    public static AdminBookingDto from(Booking booking, String seatLabel) {
        if (booking == null) return null;
        long age = Duration.between(booking.createdAt(), LocalDateTime.now()).toSeconds();
        return new AdminBookingDto(
            booking.id(),
            booking.userId(),
            booking.seatId(),
            seatLabel,
            booking.status().name(),
            booking.holdExpiresAt(),
            booking.createdAt(),
            age
        );
    }
}
