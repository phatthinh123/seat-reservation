package com.tpthinh.seatreservation.business.port.in;

import com.tpthinh.seatreservation.business.domain.model.Seat;
import java.util.List;

public interface GetSeatsUseCase {
    List<Seat> getSeats();
}
