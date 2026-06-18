package com.tpthinh.seatreservation.adapter.persistence;

import com.tpthinh.seatreservation.adapter.persistence.entity.SeatEntity;
import com.tpthinh.seatreservation.adapter.persistence.mapper.EntityMapper;
import com.tpthinh.seatreservation.adapter.persistence.repo.SeatJpaRepository;
import com.tpthinh.seatreservation.business.domain.model.Seat;
import com.tpthinh.seatreservation.business.port.external.SeatRepositoryPort;
import org.springframework.stereotype.Component;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Component
public class SeatJpaAdapter implements SeatRepositoryPort {
    private final SeatJpaRepository repository;
    private final EntityMapper mapper;

    public SeatJpaAdapter(SeatJpaRepository repository, EntityMapper mapper) {
        this.repository = repository;
        this.mapper = mapper;
    }

    @Override
    public Optional<Seat> findById(UUID id) {
        return repository.findById(id).map(mapper::toDomain);
    }

    @Override
    public Optional<Seat> findByIdForUpdate(UUID id) {
        return repository.findByIdForUpdate(id).map(mapper::toDomain);
    }

    @Override
    public List<Seat> findAll() {
        return repository.findAll().stream().map(mapper::toDomain).toList();
    }

    @Override
    public Seat save(Seat seat) {
        SeatEntity entity = mapper.fromDomain(seat);
        return mapper.toDomain(repository.save(entity));
    }
}
