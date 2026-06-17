package com.linkz.seatreservation.business.port.in;

import com.linkz.seatreservation.business.domain.model.Seat;
import java.util.List;

public interface GetSeatsUseCase {
    List<Seat> getSeats();
}
