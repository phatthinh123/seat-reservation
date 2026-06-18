package com.tpthinh.seatreservation.business.port.in;

import com.tpthinh.seatreservation.business.domain.model.Booking;
import java.util.UUID;

public interface HoldSeatUseCase {
    BookingResult holdSeat(HoldSeatCommand command);

    record HoldSeatCommand(UUID seatId, String userId, String idempotencyKey) {}
    record BookingResult(Booking booking, String seatLabel) {}
}
