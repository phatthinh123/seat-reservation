package com.linkz.seatreservation.business;

import com.linkz.seatreservation.business.domain.enums.BookingStatus;
import com.linkz.seatreservation.business.domain.enums.SeatStatus;
import com.linkz.seatreservation.business.domain.exception.SeatUnavailableException;
import com.linkz.seatreservation.business.domain.model.Booking;
import com.linkz.seatreservation.business.domain.model.Seat;
import com.linkz.seatreservation.business.port.in.HoldSeatUseCase;
import com.linkz.seatreservation.business.port.external.*;
import com.linkz.seatreservation.business.service.BookingService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.support.TransactionCallback;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.LocalDateTime;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * Pure unit tests for BookingService — no Spring context, no I/O, all collaborators mocked.
 *
 * Naming convention: test<MethodUnderTest>_<context>_<expectedOutcome>
 * All tests follow BDD structure:
 *   // Given — set up preconditions
 *   // When  — invoke the method under test
 *   // Then  — assert the expected outcome
 */
class BookingServiceTest {

    SeatRepositoryPort seatRepo = mock(SeatRepositoryPort.class);
    BookingRepositoryPort bookingRepo = mock(BookingRepositoryPort.class);
    DistributedLockPort lock = mock(DistributedLockPort.class);
    CachePort cache = mock(CachePort.class);
    AuditPort audit = mock(AuditPort.class);
    TransactionTemplate transactionTemplate = mock(TransactionTemplate.class);

    BookingService service = new BookingService(seatRepo, bookingRepo, lock, cache, audit, transactionTemplate);

    @BeforeEach
    void setUp() {
        reset(seatRepo, bookingRepo, lock, cache, audit, transactionTemplate);
        // Stub TransactionTemplate to execute the callback inline (no real DB transaction)
        when(transactionTemplate.execute(any())).thenAnswer(invocation -> {
            TransactionCallback<?> callback = invocation.getArgument(0);
            return callback.doInTransaction(null);
        });
    }

    // ─── Happy Path ───────────────────────────────────────────────────────────

    /**
     * Happy path:
     * When an available seat is held by a user with a new idempotency key,
     * a PENDING booking is persisted and the seat becomes HELD in the repository.
     */
    @Test
    void testHoldSeat_seatAvailable_shouldCreatePendingBookingAndMarkSeatHeld() {
        // Given — seat is AVAILABLE, no prior idempotency entry, lock succeeds
        UUID seatId = UUID.randomUUID();
        String userId = "user-123";
        String idempotencyKey = "key-123";
        Seat availableSeat = new Seat(seatId, "A1", SeatStatus.AVAILABLE, 0L);
        Booking expectedBooking = new Booking(
            UUID.randomUUID(), userId, seatId, BookingStatus.PENDING,
            idempotencyKey, LocalDateTime.now().plusMinutes(10),
            LocalDateTime.now(), LocalDateTime.now()
        );

        when(cache.get(eq("idempotency:key:" + idempotencyKey), eq(Booking.class))).thenReturn(null);
        when(cache.setIfAbsent(eq("idempotency:lock:" + idempotencyKey), any(), anyLong())).thenReturn(true);
        when(cache.get(eq("seat:cache:" + seatId), eq(Seat.class))).thenReturn(null);
        when(lock.tryLock(eq(seatId.toString()), anyLong(), anyLong())).thenReturn(true);
        when(seatRepo.findByIdForUpdate(seatId)).thenReturn(Optional.of(availableSeat));
        when(seatRepo.findById(seatId)).thenReturn(Optional.of(availableSeat));
        when(bookingRepo.findByIdempotencyKey(idempotencyKey)).thenReturn(Optional.empty());
        when(seatRepo.save(any(Seat.class))).thenAnswer(inv -> inv.getArgument(0));
        when(bookingRepo.save(any(Booking.class))).thenAnswer(inv -> inv.getArgument(0));

        // When — the hold is requested
        HoldSeatUseCase.BookingResult result = service.holdSeat(
            new HoldSeatUseCase.HoldSeatCommand(seatId, userId, idempotencyKey)
        );

        // Then — booking is PENDING, seat label is returned, lock is released
        assertThat(result.booking().status()).isEqualTo(BookingStatus.PENDING);
        assertThat(result.seatLabel()).isEqualTo("A1");
        verify(bookingRepo).save(any(Booking.class));
        verify(seatRepo).save(argThat(s -> s.status() == SeatStatus.HELD));
        verify(lock).unlock(seatId.toString());
    }

    /**
     * Happy path (idempotency):
     * When the same idempotency key arrives a second time and the booking
     * is already cached, the existing booking is returned immediately
     * without touching the lock, the database, or saving anything new.
     */
    @Test
    void testHoldSeat_duplicateIdempotencyKey_shouldReturnExistingBookingWithoutReserving() {
        // Given — the idempotency cache already holds a completed booking
        UUID seatId = UUID.randomUUID();
        String userId = "user-123";
        String idempotencyKey = "key-123";
        Booking existingBooking = new Booking(
            UUID.randomUUID(), userId, seatId, BookingStatus.PENDING,
            idempotencyKey, LocalDateTime.now().plusMinutes(10),
            LocalDateTime.now(), LocalDateTime.now()
        );
        Seat cachedSeat = new Seat(seatId, "A1", SeatStatus.HELD, userId, existingBooking.id(), idempotencyKey, 0L);
        when(cache.get(eq("seat:cache:" + seatId), eq(Seat.class))).thenReturn(cachedSeat);
        when(bookingRepo.findByIdempotencyKey(idempotencyKey)).thenReturn(Optional.of(existingBooking));
        when(seatRepo.findById(seatId)).thenReturn(Optional.of(cachedSeat));

        // When — the same request is replayed
        HoldSeatUseCase.BookingResult result = service.holdSeat(
            new HoldSeatUseCase.HoldSeatCommand(seatId, userId, idempotencyKey)
        );

        // Then — the cached booking is returned; nothing new is saved or locked
        assertThat(result.booking()).isEqualTo(existingBooking);
        assertThat(result.seatLabel()).isEqualTo("A1");
        verify(bookingRepo, never()).save(any());
        verify(lock, never()).tryLock(any(), anyLong(), anyLong());
    }

    // ─── Non-Happy Path ───────────────────────────────────────────────────────

    /**
     * Non-happy path (seat already held):
     * When the Redis cache shows the seat is HELD by someone else,
     * the service must fail fast with SeatUnavailableException
     * before ever acquiring a lock or hitting the database.
     */
    @Test
    void testHoldSeat_seatAlreadyHeld_shouldThrowSeatUnavailableException() {
        // Given — the seat cache reveals the seat is already HELD
        UUID seatId = UUID.randomUUID();
        String userId = "user-123";
        String idempotencyKey = "key-123";
        Seat heldSeat = new Seat(seatId, "A1", SeatStatus.HELD, 0L);

        when(cache.get(eq("idempotency:key:" + idempotencyKey), eq(Booking.class))).thenReturn(null);
        when(cache.setIfAbsent(eq("idempotency:lock:" + idempotencyKey), any(), anyLong())).thenReturn(true);
        when(cache.get(eq("seat:cache:" + seatId), eq(Seat.class))).thenReturn(heldSeat);

        // When / Then — SeatUnavailableException is thrown without any DB writes
        assertThatThrownBy(() ->
            service.holdSeat(new HoldSeatUseCase.HoldSeatCommand(seatId, userId, idempotencyKey))
        ).isInstanceOf(SeatUnavailableException.class);

        verify(bookingRepo, never()).save(any());
    }

    /**
     * Non-happy path (lock contention):
     * When the distributed lock cannot be acquired within the timeout
     * (another thread holds the lock), the service must throw
     * SeatUnavailableException immediately — not block indefinitely.
     */
    @Test
    void testHoldSeat_distributedLockTimeout_shouldThrowSeatUnavailableException() {
        // Given — cache shows seat is AVAILABLE but the distributed lock fails
        UUID seatId = UUID.randomUUID();
        String userId = "user-123";
        String idempotencyKey = "key-123";

        when(cache.get(eq("idempotency:key:" + idempotencyKey), eq(Booking.class))).thenReturn(null);
        when(cache.setIfAbsent(eq("idempotency:lock:" + idempotencyKey), any(), anyLong())).thenReturn(true);
        when(cache.get(eq("seat:cache:" + seatId), eq(Seat.class))).thenReturn(null);
        when(lock.tryLock(eq(seatId.toString()), anyLong(), anyLong())).thenReturn(false);

        // When / Then — SeatUnavailableException is thrown with a meaningful message
        assertThatThrownBy(() ->
            service.holdSeat(new HoldSeatUseCase.HoldSeatCommand(seatId, userId, idempotencyKey))
        )
            .isInstanceOf(SeatUnavailableException.class)
            .hasMessageContaining("Seat temporarily locked");

        verify(bookingRepo, never()).save(any());
    }
}
