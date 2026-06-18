package com.tpthinh.seatreservation.adapter.persistence.repo;

import com.tpthinh.seatreservation.adapter.persistence.entity.SeatEntity;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import java.util.Optional;
import java.util.UUID;

public interface SeatJpaRepository extends JpaRepository<SeatEntity, UUID> {
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT s FROM SeatEntity s WHERE s.id = :id")
    Optional<SeatEntity> findByIdForUpdate(@Param("id") UUID id);
}
