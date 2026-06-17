package com.linkz.seatreservation.business.port.external;

import com.linkz.seatreservation.business.domain.model.Seat;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface SeatRepositoryPort {
    Optional<Seat> findById(UUID id);
    Optional<Seat> findByIdForUpdate(UUID id);
    List<Seat> findAll();
    Seat save(Seat seat);
}
