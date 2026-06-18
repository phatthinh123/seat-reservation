package com.linkz.seatreservation.business.service;

import com.linkz.seatreservation.business.domain.enums.BookingStatus;
import com.linkz.seatreservation.business.domain.enums.SeatStatus;
import com.linkz.seatreservation.business.domain.exception.DuplicateRequestException;
import com.linkz.seatreservation.business.domain.exception.SeatUnavailableException;
import com.linkz.seatreservation.business.domain.model.Booking;
import com.linkz.seatreservation.business.domain.model.Seat;
import com.linkz.seatreservation.business.port.in.HoldSeatUseCase;
import com.linkz.seatreservation.business.domain.event.AuditEvents.*;
import com.linkz.seatreservation.business.port.external.BookingRepositoryPort;
import com.linkz.seatreservation.business.port.external.CachePort;
import com.linkz.seatreservation.business.port.external.DistributedLockPort;
import com.linkz.seatreservation.business.port.external.SeatRepositoryPort;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;
import java.time.LocalDateTime;
import java.util.Optional;
import java.util.UUID;

@Service
public class BookingService implements HoldSeatUseCase {
    private final SeatRepositoryPort seatRepo;
    private final BookingRepositoryPort bookingRepo;
    private final DistributedLockPort lockPort;
    private final CachePort cachePort;
    private final ApplicationEventPublisher eventPublisher;
    private final TransactionTemplate transactionTemplate;

    public BookingService(SeatRepositoryPort seatRepo,
                          BookingRepositoryPort bookingRepo,
                          DistributedLockPort lockPort,
                          CachePort cachePort,
                          ApplicationEventPublisher eventPublisher,
                          TransactionTemplate transactionTemplate) {
        this.seatRepo = seatRepo;
        this.bookingRepo = bookingRepo;
        this.lockPort = lockPort;
        this.cachePort = cachePort;
        this.eventPublisher = eventPublisher;
        this.transactionTemplate = transactionTemplate;
    }

    @Override
    public BookingResult holdSeat(HoldSeatCommand cmd) {
        String idempotencyKey = cmd.idempotencyKey();

        // 1. Consolidated Idempotency & Seat Status Check (Layer 0.5 & Layer 0.8)
        Seat cachedSeat = cachePort.get("seat:cache:" + cmd.seatId(), Seat.class);
        if (cachedSeat != null) {
            // Check if this request is a retry of the same idempotency key
            if (idempotencyKey.equals(cachedSeat.idempotencyKey())) {
                Booking booking = bookingRepo.findByIdempotencyKey(idempotencyKey).orElse(null);
                if (booking != null) {
                    return new BookingResult(booking, cachedSeat.label());
                }
            }
            // If the seat is not available (held/reserved by someone else), fail fast
            if (cachedSeat.status() != SeatStatus.AVAILABLE) {
                throw new SeatUnavailableException("Seat is not available.");
            }
        }

        // 2. Idempotency Lock (SETNX lock with 10s TTL) (Layer 0.6)
        boolean lockAcquired = cachePort.setIfAbsent("idempotency:lock:" + idempotencyKey, "PROCESSING", 10);
        if (!lockAcquired) {
            throw new DuplicateRequestException("Request is currently being processed.");
        }

        try {
            // 4. Proceed to Distributed Lock (Layer 1)
            boolean distributedLockAcquired = lockPort.tryLock(cmd.seatId().toString(), 500, 5000);
            if (!distributedLockAcquired) {
                throw new SeatUnavailableException("Seat temporarily locked, please retry");
            }

            try {
                // 5. Proceed to Database Transaction
                Booking booking = transactionTemplate.execute(status -> {
                    Seat seat = seatRepo.findByIdForUpdate(cmd.seatId())
                        .orElseThrow(() -> new SeatUnavailableException("Seat not found"));

                    if (seat.status() != SeatStatus.AVAILABLE) {
                        throw new SeatUnavailableException("Seat is already " + seat.status());
                    }

                    Optional<Booking> existing = bookingRepo.findByIdempotencyKey(cmd.idempotencyKey());
                    if (existing.isPresent()) {
                        return existing.get();
                    }

                    Seat heldSeat = new Seat(seat.id(), seat.label(), SeatStatus.HELD, seat.version());
                    Seat savedSeat = seatRepo.save(heldSeat);

                    Booking newBooking = new Booking(
                        UUID.randomUUID(),
                        cmd.userId(),
                        cmd.seatId(),
                        BookingStatus.PENDING,
                        cmd.idempotencyKey(),
                        LocalDateTime.now().plusMinutes(10),
                        LocalDateTime.now(),
                        LocalDateTime.now()
                    );
                    Booking savedBooking = bookingRepo.save(newBooking);

                    eventPublisher.publishEvent(new SeatHeldEvent(seat, savedSeat));
                    eventPublisher.publishEvent(new BookingCreatedEvent(cmd.userId(), savedBooking));

                    return savedBooking;
                });

                // 6. Overwrite Redis cache write-through (combining seat status & hold/idempotency info)
                String seatLabel = "Unknown";
                if (booking != null) {
                    Seat updatedSeat = seatRepo.findById(cmd.seatId()).orElse(null);
                    if (updatedSeat != null) {
                        Seat cachedSeatWithHoldInfo = new Seat(
                            updatedSeat.id(),
                            updatedSeat.label(),
                            updatedSeat.status(),
                            booking.userId(),
                            booking.id(),
                            booking.idempotencyKey(),
                            updatedSeat.version()
                        );
                        cachePort.put("seat:cache:" + cmd.seatId(), cachedSeatWithHoldInfo, 24 * 3600);
                        seatLabel = updatedSeat.label();
                    }
                }

                return new BookingResult(booking, seatLabel);
            } finally {
                lockPort.unlock(cmd.seatId().toString());
            }
        } finally {
            cachePort.evict("idempotency:lock:" + idempotencyKey);
        }
    }
}
