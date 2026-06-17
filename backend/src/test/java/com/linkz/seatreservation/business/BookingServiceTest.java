package com.linkz.seatreservation.business;

import com.linkz.seatreservation.business.domain.enums.BookingStatus;
import com.linkz.seatreservation.business.domain.enums.SeatStatus;
import com.linkz.seatreservation.business.domain.exception.DuplicateRequestException;
import com.linkz.seatreservation.business.domain.exception.SeatUnavailableException;
import com.linkz.seatreservation.business.domain.model.Booking;
import com.linkz.seatreservation.business.domain.model.Seat;
import com.linkz.seatreservation.business.port.in.HoldSeatUseCase;
import com.linkz.seatreservation.business.port.out.*;
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
        
        // Stub transactionTemplate to execute callback immediately
        when(transactionTemplate.execute(any())).thenAnswer(invocation -> {
            TransactionCallback<?> callback = invocation.getArgument(0);
            return callback.doInTransaction(null);
        });
    }

    @Test
    void holdSeat_whenAvailable_shouldCreatePendingBooking() {
        UUID seatId = UUID.randomUUID();
        String userId = "user-123";
        String idempotencyKey = "key-123";
        Seat seat = new Seat(seatId, "A1", SeatStatus.AVAILABLE, 0L);
        Booking booking = new Booking(UUID.randomUUID(), userId, seatId, BookingStatus.PENDING, idempotencyKey, LocalDateTime.now().plusMinutes(10), LocalDateTime.now(), LocalDateTime.now());

        // Mocks for CachePort
        when(cache.get(eq("idempotency:key:" + idempotencyKey), eq(Booking.class))).thenReturn(null);
        when(cache.setIfAbsent(eq("idempotency:lock:" + idempotencyKey), any(), anyLong())).thenReturn(true);
        when(cache.get(eq("seat:cache:" + seatId), eq(Seat.class))).thenReturn(null);

        // Mocks for Lock
        when(lock.tryLock(eq(seatId.toString()), anyLong(), anyLong())).thenReturn(true);

        // Mocks for Repo
        when(seatRepo.findByIdForUpdate(seatId)).thenReturn(Optional.of(seat));
        when(seatRepo.findById(seatId)).thenReturn(Optional.of(seat));
        when(bookingRepo.findByIdempotencyKey(idempotencyKey)).thenReturn(Optional.empty());
        when(seatRepo.save(any(Seat.class))).thenAnswer(invocation -> invocation.getArgument(0));
        when(bookingRepo.save(any(Booking.class))).thenAnswer(invocation -> invocation.getArgument(0));

        HoldSeatUseCase.BookingResult result = service.holdSeat(new HoldSeatUseCase.HoldSeatCommand(seatId, userId, idempotencyKey));

        assertThat(result.booking().status()).isEqualTo(BookingStatus.PENDING);
        assertThat(result.seatLabel()).isEqualTo("A1");
        
        verify(bookingRepo).save(any(Booking.class));
        verify(seatRepo).save(argThat(s -> s.status() == SeatStatus.HELD));
        verify(lock).unlock(seatId.toString());
    }

    @Test
    void holdSeat_whenHeld_shouldThrowSeatUnavailableException() {
        UUID seatId = UUID.randomUUID();
        String userId = "user-123";
        String idempotencyKey = "key-123";
        Seat seat = new Seat(seatId, "A1", SeatStatus.HELD, 0L);

        // Mocks for CachePort
        when(cache.get(eq("idempotency:key:" + idempotencyKey), eq(Booking.class))).thenReturn(null);
        when(cache.setIfAbsent(eq("idempotency:lock:" + idempotencyKey), any(), anyLong())).thenReturn(true);
        
        // Mocks for CachePort seat:cache check (fail fast)
        when(cache.get(eq("seat:cache:" + seatId), eq(Seat.class))).thenReturn(seat);

        HoldSeatUseCase.HoldSeatCommand cmd = new HoldSeatUseCase.HoldSeatCommand(seatId, userId, idempotencyKey);
        
        assertThatThrownBy(() -> service.holdSeat(cmd))
            .isInstanceOf(SeatUnavailableException.class);
            
        verify(bookingRepo, never()).save(any());
    }

    @Test
    void holdSeat_withSameIdempotencyKey_shouldReturnExistingBooking() {
        UUID seatId = UUID.randomUUID();
        String userId = "user-123";
        String idempotencyKey = "key-123";
        Booking existingBooking = new Booking(UUID.randomUUID(), userId, seatId, BookingStatus.PENDING, idempotencyKey, LocalDateTime.now().plusMinutes(10), LocalDateTime.now(), LocalDateTime.now());

        // CachePort returns existing booking
        when(cache.get(eq("idempotency:key:" + idempotencyKey), eq(Booking.class))).thenReturn(existingBooking);
        when(seatRepo.findById(seatId)).thenReturn(Optional.of(new Seat(seatId, "A1", SeatStatus.HELD, 0L)));

        HoldSeatUseCase.BookingResult result = service.holdSeat(new HoldSeatUseCase.HoldSeatCommand(seatId, userId, idempotencyKey));

        assertThat(result.booking()).isEqualTo(existingBooking);
        assertThat(result.seatLabel()).isEqualTo("A1");
        
        verify(bookingRepo, never()).save(any());
        verify(lock, never()).tryLock(any(), anyLong(), anyLong());
    }

    @Test
    void holdSeat_whenLockFails_shouldThrowSeatUnavailableException() {
        UUID seatId = UUID.randomUUID();
        String userId = "user-123";
        String idempotencyKey = "key-123";

        // Mocks for CachePort
        when(cache.get(eq("idempotency:key:" + idempotencyKey), eq(Booking.class))).thenReturn(null);
        when(cache.setIfAbsent(eq("idempotency:lock:" + idempotencyKey), any(), anyLong())).thenReturn(true);
        when(cache.get(eq("seat:cache:" + seatId), eq(Seat.class))).thenReturn(null);

        // Mocks for Lock returning false
        when(lock.tryLock(eq(seatId.toString()), anyLong(), anyLong())).thenReturn(false);

        HoldSeatUseCase.HoldSeatCommand cmd = new HoldSeatUseCase.HoldSeatCommand(seatId, userId, idempotencyKey);

        assertThatThrownBy(() -> service.holdSeat(cmd))
            .isInstanceOf(SeatUnavailableException.class)
            .hasMessageContaining("Seat temporarily locked");

        verify(bookingRepo, never()).save(any());
    }
}
