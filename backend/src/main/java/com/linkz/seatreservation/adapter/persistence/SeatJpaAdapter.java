package com.linkz.seatreservation.adapter.persistence;

import com.linkz.seatreservation.adapter.persistence.entity.SeatEntity;
import com.linkz.seatreservation.adapter.persistence.repo.SeatJpaRepository;
import com.linkz.seatreservation.business.domain.model.Seat;
import com.linkz.seatreservation.business.port.out.SeatRepositoryPort;
import org.springframework.stereotype.Component;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Component
public class SeatJpaAdapter implements SeatRepositoryPort {
    private final SeatJpaRepository repository;

    public SeatJpaAdapter(SeatJpaRepository repository) {
        this.repository = repository;
    }

    @Override
    public Optional<Seat> findById(UUID id) {
        return repository.findById(id).map(SeatEntity::toDomain);
    }

    @Override
    public Optional<Seat> findByIdForUpdate(UUID id) {
        return repository.findByIdForUpdate(id).map(SeatEntity::toDomain);
    }

    @Override
    public List<Seat> findAll() {
        return repository.findAll().stream().map(SeatEntity::toDomain).toList();
    }

    @Override
    public Seat save(Seat seat) {
        SeatEntity entity = SeatEntity.fromDomain(seat);
        return repository.save(entity).toDomain();
    }
}
