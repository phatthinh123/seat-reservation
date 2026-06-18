package com.linkz.seatreservation.business.domain.model;

import com.linkz.seatreservation.business.domain.enums.SeatStatus;
import java.util.UUID;

public record Seat(
    UUID id,
    String label,
    SeatStatus status,
    String heldBy,
    UUID bookingId,
    String idempotencyKey,
    Long version
) {
    public Seat(UUID id, String label, SeatStatus status, Long version) {
        this(id, label, status, null, null, null, version);
    }
}
