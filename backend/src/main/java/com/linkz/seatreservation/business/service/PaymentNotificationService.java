package com.linkz.seatreservation.business.service;

import com.linkz.seatreservation.business.domain.enums.*;
import com.linkz.seatreservation.business.domain.model.*;
import com.linkz.seatreservation.business.port.in.HandlePaymentNotificationUseCase;
import com.linkz.seatreservation.business.port.external.*;
import com.linkz.seatreservation.business.domain.event.AuditEvents.*;
import lombok.RequiredArgsConstructor;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

@Service
@RequiredArgsConstructor
public class PaymentNotificationService implements HandlePaymentNotificationUseCase {
    private static final org.slf4j.Logger log = org.slf4j.LoggerFactory.getLogger(PaymentNotificationService.class);

    private final BookingRepositoryPort bookingRepo;
    private final SeatRepositoryPort seatRepo;
    private final PaymentRepositoryPort paymentRepo;
    private final PaymentNotificationRepositoryPort paymentNotificationRepo;
    private final PaymentGatewayPort paymentGateway;
    private final CachePort cachePort;
    private final ApplicationEventPublisher eventPublisher;
    private final TransactionTemplate transactionTemplate;

    @Override
    public void handleNotification(PaymentNotificationCommand cmd) {
        // Step 1: Check if the event was already processed or marked duplicate
        var existingStatus = paymentNotificationRepo.findStatusByEventId(cmd.eventId());
        if (existingStatus.isPresent()) {
            var status = existingStatus.get();
            if ("PROCESSED".equals(status) || "DUPLICATE".equals(status)) {
                eventPublisher.publishEvent(new PaymentNotificationDuplicateEvent(cmd.eventId(), cmd.rawPayload(), "Duplicate event ID checked"));
                paymentNotificationRepo.saveEvent("mock-payment", cmd.eventId(), cmd.rawPayload(), "DUPLICATE", "Duplicate event detected");
                return;
            }
        }

        // Step 2: Persist raw payload to database IMMEDIATELY — before ANY logic.
        try {
            paymentNotificationRepo.saveEvent("mock-payment", cmd.eventId(), cmd.rawPayload(), "RECEIVED", null);
        } catch (org.springframework.dao.DataIntegrityViolationException e) {
            // Concurrent duplicate event caught via database UNIQUE constraint on event_id.
            // Return gracefully to return HTTP 200 OK back to the payment provider.
            eventPublisher.publishEvent(new PaymentNotificationDuplicateEvent(cmd.eventId(), cmd.rawPayload(), "Concurrent duplicate event ID"));
            return;
        }
        eventPublisher.publishEvent(new PaymentNotificationReceivedEvent(cmd.eventId(), cmd.rawPayload()));

        try {
            // Step 3: Run processing inside transaction and return the external payment ID if a refund is required
            String refundExternalId = transactionTemplate.execute(status -> {
                var booking = bookingRepo.findByExternalPaymentIdForUpdate(cmd.paymentId())
                    .orElseThrow(() -> new RuntimeException("Booking not found for payment ID: " + cmd.paymentId()));

                var seat = seatRepo.findByIdForUpdate(booking.seatId())
                    .orElseThrow(() -> new RuntimeException("Seat not found for booking: " + booking.id()));

                var payment = paymentRepo.findByExternalPaymentId(cmd.paymentId())
                    .orElseThrow(() -> new RuntimeException("Payment transaction not found for external payment ID: " + cmd.paymentId()));

                return switch (booking.status()) {
                    case PENDING -> {
                        handlePendingNotification(booking, seat, payment, cmd);
                        yield null;
                    }
                    case EXPIRED, CANCELLED -> handleLateNotification(booking, payment, cmd);
                    case CONFIRMED -> {
                        handleConfirmedNotification(booking, cmd);
                        yield null;
                    }
                };
            });

            // Step 4: Execute external payment gateway API call OUTSIDE of db transaction
            if (refundExternalId != null) {
                try {
                    paymentGateway.refund(refundExternalId);
                } catch (Exception e) {
                    log.error("Failed to execute external refund call for external payment ID: " + refundExternalId, e);
                }
            }
        } catch (Exception e) {
            paymentNotificationRepo.saveEvent("mock-payment", cmd.eventId(), cmd.rawPayload(), "FAILED", e.getMessage());
            throw e;
        }
    }

    private void handlePendingNotification(Booking booking, Seat seat, Payment payment, PaymentNotificationCommand cmd) {
        if ("SUCCESS".equalsIgnoreCase(cmd.status())) {
            confirmBooking(booking, seat, payment, cmd);
        } else {
            failBooking(booking, seat, payment, cmd);
        }
    }

    private String handleLateNotification(Booking booking, Payment payment, PaymentNotificationCommand cmd) {
        if ("SUCCESS".equalsIgnoreCase(cmd.status())) {
            handleLateArrival(booking, payment, cmd);
            return payment.externalPaymentId();
        } else {
            paymentNotificationRepo.saveEvent("mock-payment", cmd.eventId(), cmd.rawPayload(), "PROCESSED", null);
            eventPublisher.publishEvent(new PaymentNotificationProcessedEvent(cmd.eventId(), cmd.rawPayload(), "Failed payment for already expired/cancelled booking"));
            return null;
        }
    }

    private void handleConfirmedNotification(Booking booking, PaymentNotificationCommand cmd) {
        eventPublisher.publishEvent(new BookingDuplicateNotificationEvent(booking, cmd.eventId(), cmd.rawPayload()));
        paymentNotificationRepo.saveEvent("mock-payment", cmd.eventId(), cmd.rawPayload(), "PROCESSED", "Duplicate webhook for confirmed booking");
    }

    private void confirmBooking(Booking booking, Seat seat, Payment payment, PaymentNotificationCommand cmd) {
        var confirmedBooking = new Booking(
            booking.id(), booking.userId(), booking.seatId(), BookingStatus.CONFIRMED,
            booking.idempotencyKey(), booking.holdExpiresAt(), booking.createdAt(), java.time.LocalDateTime.now()
        );
        var savedBooking = bookingRepo.save(confirmedBooking);

        var reservedSeat = new Seat(seat.id(), seat.label(), SeatStatus.RESERVED, seat.version());
        var savedSeat = seatRepo.save(reservedSeat);

        var successPayment = new Payment(
            payment.id(), payment.bookingId(), payment.externalPaymentId(), payment.amount(),
            PaymentStatus.SUCCESS, payment.rawPayload(), payment.createdAt(), java.time.LocalDateTime.now()
        );
        var savedPayment = paymentRepo.save(successPayment);

        paymentNotificationRepo.saveEvent("mock-payment", cmd.eventId(), "", "PROCESSED", null);

        var cachedSeatWithHoldInfo = new Seat(
            savedSeat != null ? savedSeat.id() : seat.id(),
            savedSeat != null ? savedSeat.label() : seat.label(),
            savedSeat != null ? savedSeat.status() : SeatStatus.RESERVED,
            booking.userId(),
            booking.id(),
            booking.idempotencyKey(),
            savedSeat != null ? savedSeat.version() : seat.version()
        );
        cachePort.put("seat:cache:" + seat.id(), cachedSeatWithHoldInfo, 24 * 3600);

        eventPublisher.publishEvent(new BookingConfirmedEvent(
            booking, savedBooking,
            seat, savedSeat,
            payment, savedPayment,
            cmd.eventId()
        ));
    }

    private void failBooking(Booking booking, Seat seat, Payment payment, PaymentNotificationCommand cmd) {
        var cancelledBooking = new Booking(
            booking.id(), booking.userId(), booking.seatId(), BookingStatus.CANCELLED,
            booking.idempotencyKey(), booking.holdExpiresAt(), booking.createdAt(), java.time.LocalDateTime.now()
        );
        var savedBooking = bookingRepo.save(cancelledBooking);

        var availableSeat = new Seat(seat.id(), seat.label(), SeatStatus.AVAILABLE, seat.version());
        var savedSeat = seatRepo.save(availableSeat);

        var failedPayment = new Payment(
            payment.id(), payment.bookingId(), payment.externalPaymentId(), payment.amount(),
            PaymentStatus.FAILED, payment.rawPayload(), payment.createdAt(), java.time.LocalDateTime.now()
        );
        var savedPayment = paymentRepo.save(failedPayment);

        paymentNotificationRepo.saveEvent("mock-payment", cmd.eventId(), "", "PROCESSED", null);

        cachePort.put("seat:cache:" + seat.id(), savedSeat, 24 * 3600);

        eventPublisher.publishEvent(new BookingCancelledEvent(
            booking, savedBooking,
            seat, savedSeat,
            payment, savedPayment,
            cmd.eventId()
        ));
    }

    private void handleLateArrival(Booking booking, Payment payment, PaymentNotificationCommand cmd) {
        var refundedPayment = new Payment(
            payment.id(), payment.bookingId(), payment.externalPaymentId(), payment.amount(),
            PaymentStatus.REFUNDED, payment.rawPayload(), payment.createdAt(), java.time.LocalDateTime.now()
        );
        var savedPayment = paymentRepo.save(refundedPayment);

        paymentNotificationRepo.saveEvent("mock-payment", cmd.eventId(), "", "PROCESSED", null);

        eventPublisher.publishEvent(new BookingLateRefundEvent(booking, payment, savedPayment, cmd.eventId()));
    }
}
