package com.linkz.seatreservation.web.dto.response;

import com.linkz.seatreservation.business.domain.enums.SeatStatus;
import com.linkz.seatreservation.business.domain.model.Seat;
import java.util.UUID;

public record SeatResponse(UUID id, String label, SeatStatus status) {
    public static SeatResponse from(Seat seat) {
        if (seat == null) return null;
        return new SeatResponse(seat.id(), seat.label(), seat.status());
    }
}
