package com.tpthinh.seatreservation.business.port.in;

import com.tpthinh.seatreservation.business.domain.model.Booking;
import java.util.List;

public interface GetPendingBookingsUseCase {
    List<PendingBookingResult> getPendingBookings();

    record PendingBookingResult(Booking booking, String seatLabel) {}
}
