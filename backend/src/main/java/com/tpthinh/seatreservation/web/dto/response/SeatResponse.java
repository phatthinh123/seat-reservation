package com.tpthinh.seatreservation.web.dto.response;

import com.tpthinh.seatreservation.business.domain.enums.SeatStatus;
import com.tpthinh.seatreservation.business.domain.model.Seat;
import java.util.UUID;

public record SeatResponse(UUID id, String label, SeatStatus status, String heldBy) {
    public static SeatResponse from(Seat seat) {
        if (seat == null) return null;
        return new SeatResponse(seat.id(), seat.label(), seat.status(), seat.heldBy());
    }
}
