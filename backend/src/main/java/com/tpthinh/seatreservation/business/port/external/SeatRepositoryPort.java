package com.tpthinh.seatreservation.business.port.external;

import com.tpthinh.seatreservation.business.domain.model.Seat;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface SeatRepositoryPort {
    Optional<Seat> findById(UUID id);
    Optional<Seat> findByIdForUpdate(UUID id);
    List<Seat> findAll();
    Seat save(Seat seat);
}
