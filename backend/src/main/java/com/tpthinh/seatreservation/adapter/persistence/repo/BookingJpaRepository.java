package com.tpthinh.seatreservation.adapter.persistence.repo;

import com.tpthinh.seatreservation.adapter.persistence.entity.BookingEntity;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface BookingJpaRepository extends JpaRepository<BookingEntity, UUID> {
    
    Optional<BookingEntity> findByIdempotencyKey(String idempotencyKey);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT b FROM BookingEntity b WHERE b.id = :id")
    Optional<BookingEntity> findByIdForUpdate(@Param("id") UUID id);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT b FROM BookingEntity b WHERE b.id = (SELECT p.bookingId FROM PaymentTransactionEntity p WHERE p.externalPaymentId = :externalPaymentId)")
    Optional<BookingEntity> findByExternalPaymentIdForUpdate(@Param("externalPaymentId") String externalPaymentId);

    @Query("SELECT b FROM BookingEntity b WHERE b.status = com.tpthinh.seatreservation.business.domain.enums.BookingStatus.PENDING AND b.holdExpiresAt <= :now")
    List<BookingEntity> findExpiredPending(@Param("now") LocalDateTime now);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT b FROM BookingEntity b WHERE b.status = com.tpthinh.seatreservation.business.domain.enums.BookingStatus.PENDING AND b.holdExpiresAt <= :now")
    List<BookingEntity> findExpiredPendingForUpdate(@Param("now") LocalDateTime now);

    @Query("SELECT b FROM BookingEntity b WHERE b.status = com.tpthinh.seatreservation.business.domain.enums.BookingStatus.PENDING")
    List<BookingEntity> findAllPending();
}
