package com.linkz.seatreservation.business.service;

import com.linkz.seatreservation.business.domain.enums.*;
import com.linkz.seatreservation.business.domain.model.*;
import com.linkz.seatreservation.business.port.in.ReconcilePaymentUseCase;
import com.linkz.seatreservation.business.port.external.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Service
public class ReconciliationService implements ReconcilePaymentUseCase {
    private static final Logger log = LoggerFactory.getLogger(ReconciliationService.class);

    private final BookingRepositoryPort bookingRepo;
    private final SeatRepositoryPort seatRepo;
    private final PaymentRepositoryPort paymentRepo;
    private final PaymentGatewayPort paymentGateway;
    private final CachePort cachePort;
    private final AuditPort auditPort;
    private final TransactionTemplate transactionTemplate;

    public ReconciliationService(BookingRepositoryPort bookingRepo,
                                 SeatRepositoryPort seatRepo,
                                 PaymentRepositoryPort paymentRepo,
                                 PaymentGatewayPort paymentGateway,
                                 CachePort cachePort,
                                 AuditPort auditPort,
                                 TransactionTemplate transactionTemplate) {
        this.bookingRepo = bookingRepo;
        this.seatRepo = seatRepo;
        this.paymentRepo = paymentRepo;
        this.paymentGateway = paymentGateway;
        this.cachePort = cachePort;
        this.auditPort = auditPort;
        this.transactionTemplate = transactionTemplate;
    }

    @Override
    public void reconcile(UUID bookingId) {
        log.info("Triggering manual reconciliation for booking {}", bookingId);
        auditPort.log("admin", "MANUAL_RECONCILE", "BOOKING", bookingId.toString(), null, "Manual reconcile triggered");
        
        transactionTemplate.executeWithoutResult(status -> {
            Booking booking = bookingRepo.findByIdForUpdate(bookingId)
                .orElseThrow(() -> new RuntimeException("Booking not found: " + bookingId));

            if (booking.status() != BookingStatus.PENDING) {
                log.info("Booking {} is already in state {}, skipping", bookingId, booking.status());
                return;
            }

            Optional<Payment> paymentOpt = paymentRepo.findByBookingId(bookingId);
            if (paymentOpt.isEmpty()) {
                if (booking.holdExpiresAt().isBefore(LocalDateTime.now())) {
                    expireBooking(booking);
                }
                return;
            }

            Payment payment = paymentOpt.get();
            if (payment.externalPaymentId() == null || payment.externalPaymentId().isBlank()) {
                if (booking.holdExpiresAt().isBefore(LocalDateTime.now())) {
                    expireBooking(booking);
                }
                return;
            }

            String gatewayStatus = paymentGateway.queryStatus(payment.externalPaymentId());
            processGatewayStatus(booking, payment, gatewayStatus);
        });
    }

    @Override
    public void reconcilePendingBookings() {
        log.info("Running scheduled reconciliation job");
        auditPort.log("system", "RECONCILIATION_RUN", "SYSTEM", "reconciliation", null, "Scheduled reconciliation run");

        LocalDateTime limit = LocalDateTime.now().plusMinutes(2);
        List<Booking> pendingBookings = bookingRepo.findExpiredPending(limit);

        for (Booking booking : pendingBookings) {
            try {
                transactionTemplate.executeWithoutResult(status -> {
                    Booking lockedBooking = bookingRepo.findByIdForUpdate(booking.id()).orElse(null);
                    if (lockedBooking == null || lockedBooking.status() != BookingStatus.PENDING) {
                        return;
                    }

                    Optional<Payment> paymentOpt = paymentRepo.findByBookingId(lockedBooking.id());
                    if (paymentOpt.isEmpty()) {
                        if (lockedBooking.holdExpiresAt().isBefore(LocalDateTime.now())) {
                            expireBooking(lockedBooking);
                        }
                        return;
                    }

                    Payment payment = paymentOpt.get();
                    if (payment.externalPaymentId() == null || payment.externalPaymentId().isBlank()) {
                        if (lockedBooking.holdExpiresAt().isBefore(LocalDateTime.now())) {
                            expireBooking(lockedBooking);
                        }
                        return;
                    }

                    String gatewayStatus = paymentGateway.queryStatus(payment.externalPaymentId());
                    processGatewayStatus(lockedBooking, payment, gatewayStatus);
                });
            } catch (Exception e) {
                log.error("Failed to reconcile booking {}: {}", booking.id(), e.getMessage());
            }
        }
    }

    @Override
    public void releaseExpiredHolds() {
        log.info("Running scheduled seat hold cleanup job");
        List<Booking> expired = bookingRepo.findExpiredPending(LocalDateTime.now());
        for (Booking booking : expired) {
            try {
                transactionTemplate.executeWithoutResult(status -> {
                    Booking lockedBooking = bookingRepo.findByIdForUpdate(booking.id()).orElse(null);
                    if (lockedBooking != null && lockedBooking.status() == BookingStatus.PENDING) {
                        expireBooking(lockedBooking);
                    }
                });
            } catch (Exception e) {
                log.error("Failed to release expired booking {}: {}", booking.id(), e.getMessage());
            }
        }
    }

    private void processGatewayStatus(Booking booking, Payment payment, String gatewayStatus) {
        Seat seat = seatRepo.findByIdForUpdate(booking.seatId())
            .orElseThrow(() -> new RuntimeException("Seat not found: " + booking.seatId()));

        if ("SUCCESS".equalsIgnoreCase(gatewayStatus)) {
            log.info("Reconciliation SUCCESS for booking {}, confirming", booking.id());
            confirmBooking(booking, seat, payment);
        } else if ("FAILED".equalsIgnoreCase(gatewayStatus)) {
            log.info("Reconciliation FAILED for booking {}, cancelling", booking.id());
            failBooking(booking, seat, payment);
        } else {
            if (booking.holdExpiresAt().isBefore(LocalDateTime.now())) {
                log.info("Reconciliation PENDING but booking hold expired for {}, releasing", booking.id());
                expireBooking(booking);
            }
        }
    }

    private void expireBooking(Booking booking) {
        Seat seat = seatRepo.findByIdForUpdate(booking.seatId())
            .orElseThrow(() -> new RuntimeException("Seat not found: " + booking.seatId()));

        Booking expiredBooking = new Booking(
            booking.id(), booking.userId(), booking.seatId(), BookingStatus.EXPIRED,
            booking.idempotencyKey(), booking.holdExpiresAt(), booking.createdAt(), java.time.LocalDateTime.now()
        );
        bookingRepo.save(expiredBooking);

        Seat availableSeat = new Seat(seat.id(), seat.label(), SeatStatus.AVAILABLE, seat.version());
        Seat savedSeat = seatRepo.save(availableSeat);

        paymentRepo.findByBookingId(booking.id()).ifPresent(payment -> {
            Payment failedPayment = new Payment(
                payment.id(), payment.bookingId(), payment.externalPaymentId(), payment.amount(),
                PaymentStatus.FAILED, payment.rawPayload(), payment.createdAt(), java.time.LocalDateTime.now()
            );
            paymentRepo.save(failedPayment);
            auditPort.log("system", "PAYMENT_FAILED", "PAYMENT", payment.id().toString(), payment, failedPayment);
        });

        cachePort.put("seat:cache:" + seat.id(), savedSeat, 24 * 3600);

        auditPort.log("system", "BOOKING_EXPIRED", "BOOKING", booking.id().toString(), booking, expiredBooking);
        auditPort.log("system", "SEAT_RELEASED", "SEAT", seat.id().toString(), seat, savedSeat);
    }

    private void confirmBooking(Booking booking, Seat seat, Payment payment) {
        Booking confirmedBooking = new Booking(
            booking.id(), booking.userId(), booking.seatId(), BookingStatus.CONFIRMED,
            booking.idempotencyKey(), booking.holdExpiresAt(), booking.createdAt(), java.time.LocalDateTime.now()
        );
        bookingRepo.save(confirmedBooking);

        Seat reservedSeat = new Seat(seat.id(), seat.label(), SeatStatus.RESERVED, seat.version());
        Seat savedSeat = seatRepo.save(reservedSeat);

        Payment successPayment = new Payment(
            payment.id(), payment.bookingId(), payment.externalPaymentId(), payment.amount(),
            PaymentStatus.SUCCESS, payment.rawPayload(), payment.createdAt(), java.time.LocalDateTime.now()
        );
        paymentRepo.save(successPayment);

        Seat cachedSeatWithHoldInfo = new Seat(
            savedSeat != null ? savedSeat.id() : seat.id(),
            savedSeat != null ? savedSeat.label() : seat.label(),
            savedSeat != null ? savedSeat.status() : SeatStatus.RESERVED,
            booking.userId(),
            booking.id(),
            booking.idempotencyKey(),
            savedSeat != null ? savedSeat.version() : seat.version()
        );
        cachePort.put("seat:cache:" + seat.id(), cachedSeatWithHoldInfo, 24 * 3600);

        auditPort.log("system", "BOOKING_CONFIRMED", "BOOKING", booking.id().toString(), booking, confirmedBooking);
        auditPort.log("system", "SEAT_RESERVED", "SEAT", seat.id().toString(), seat, savedSeat);
        auditPort.log("system", "PAYMENT_SUCCESS", "PAYMENT", payment.id().toString(), payment, successPayment);
    }

    private void failBooking(Booking booking, Seat seat, Payment payment) {
        Booking cancelledBooking = new Booking(
            booking.id(), booking.userId(), booking.seatId(), BookingStatus.CANCELLED,
            booking.idempotencyKey(), booking.holdExpiresAt(), booking.createdAt(), java.time.LocalDateTime.now()
        );
        bookingRepo.save(cancelledBooking);

        Seat availableSeat = new Seat(seat.id(), seat.label(), SeatStatus.AVAILABLE, seat.version());
        Seat savedSeat = seatRepo.save(availableSeat);

        Payment failedPayment = new Payment(
            payment.id(), payment.bookingId(), payment.externalPaymentId(), payment.amount(),
            PaymentStatus.FAILED, payment.rawPayload(), payment.createdAt(), java.time.LocalDateTime.now()
        );
        paymentRepo.save(failedPayment);

        cachePort.put("seat:cache:" + seat.id(), savedSeat, 24 * 3600);

        auditPort.log("system", "BOOKING_CANCELLED", "BOOKING", booking.id().toString(), booking, cancelledBooking);
        auditPort.log("system", "SEAT_RELEASED", "SEAT", seat.id().toString(), seat, savedSeat);
        auditPort.log("system", "PAYMENT_FAILED", "PAYMENT", payment.id().toString(), payment, failedPayment);
    }
}
