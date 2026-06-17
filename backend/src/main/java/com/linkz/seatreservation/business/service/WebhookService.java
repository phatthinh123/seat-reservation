package com.linkz.seatreservation.business.service;

import com.linkz.seatreservation.business.domain.enums.*;
import com.linkz.seatreservation.business.domain.model.*;
import com.linkz.seatreservation.business.port.in.HandleWebhookUseCase;
import com.linkz.seatreservation.business.port.external.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

@Service
public class WebhookService implements HandleWebhookUseCase {
    private final BookingRepositoryPort bookingRepo;
    private final SeatRepositoryPort seatRepo;
    private final PaymentRepositoryPort paymentRepo;
    private final WebhookEventRepositoryPort webhookEventRepo;
    private final PaymentGatewayPort paymentGateway;
    private final CachePort cachePort;
    private final AuditPort auditPort;
    private final TransactionTemplate transactionTemplate;

    public WebhookService(BookingRepositoryPort bookingRepo,
                          SeatRepositoryPort seatRepo,
                          PaymentRepositoryPort paymentRepo,
                          WebhookEventRepositoryPort webhookEventRepo,
                          PaymentGatewayPort paymentGateway,
                          CachePort cachePort,
                          AuditPort auditPort,
                          TransactionTemplate transactionTemplate) {
        this.bookingRepo = bookingRepo;
        this.seatRepo = seatRepo;
        this.paymentRepo = paymentRepo;
        this.webhookEventRepo = webhookEventRepo;
        this.paymentGateway = paymentGateway;
        this.cachePort = cachePort;
        this.auditPort = auditPort;
        this.transactionTemplate = transactionTemplate;
    }

    @Override
    public void handleWebhook(WebhookEventCommand cmd) {
        // Step 1: Persist raw event to webhook_events IMMEDIATELY (no transaction, save event details first)
        auditPort.log("system", "WEBHOOK_RECEIVED", "WEBHOOK", cmd.eventId(), null, cmd.rawPayload());

        // Step 2: Idempotency check via event_id UNIQUE
        if (webhookEventRepo.existsByEventId(cmd.eventId())) {
            auditPort.log("system", "WEBHOOK_DUPLICATE", "WEBHOOK", cmd.eventId(), null, "Duplicate event ID checked");
            webhookEventRepo.saveEvent("mock-payment", cmd.eventId(), cmd.rawPayload(), "DUPLICATE", "Duplicate event detected");
            return;
        }

        try {
            // Step 3: Run processing inside transaction
            transactionTemplate.executeWithoutResult(status -> {
                Booking booking = bookingRepo.findByExternalPaymentIdForUpdate(cmd.paymentId())
                    .orElseThrow(() -> new RuntimeException("Booking not found for payment ID: " + cmd.paymentId()));

                Seat seat = seatRepo.findByIdForUpdate(booking.seatId())
                    .orElseThrow(() -> new RuntimeException("Seat not found for booking: " + booking.id()));

                Payment payment = paymentRepo.findByExternalPaymentId(cmd.paymentId())
                    .orElseThrow(() -> new RuntimeException("Payment transaction not found for external payment ID: " + cmd.paymentId()));

                switch (booking.status()) {
                    case PENDING -> {
                        if ("SUCCESS".equalsIgnoreCase(cmd.status())) {
                            confirmBooking(booking, seat, payment, cmd.eventId());
                        } else {
                            failBooking(booking, seat, payment, cmd.eventId());
                        }
                    }
                    case EXPIRED, CANCELLED -> {
                        if ("SUCCESS".equalsIgnoreCase(cmd.status())) {
                            handleLateArrival(booking, payment, cmd.eventId());
                        } else {
                            webhookEventRepo.saveEvent("mock-payment", cmd.eventId(), cmd.rawPayload(), "PROCESSED", null);
                            auditPort.log("system", "WEBHOOK_PROCESSED", "WEBHOOK", cmd.eventId(), null, "Failed payment for already expired/cancelled booking");
                        }
                    }
                    case CONFIRMED -> {
                        auditPort.log("system", "WEBHOOK_DUPLICATE", "BOOKING", booking.id().toString(), booking, booking);
                        webhookEventRepo.saveEvent("mock-payment", cmd.eventId(), cmd.rawPayload(), "PROCESSED", "Duplicate webhook for confirmed booking");
                    }
                }
            });
        } catch (Exception e) {
            webhookEventRepo.saveEvent("mock-payment", cmd.eventId(), cmd.rawPayload(), "FAILED", e.getMessage());
            throw e;
        }
    }

    private void confirmBooking(Booking booking, Seat seat, Payment payment, String eventId) {
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

        webhookEventRepo.saveEvent("mock-payment", eventId, "", "PROCESSED", null);

        // Write-through cache updates
        cachePort.put("seat:cache:" + seat.id(), savedSeat, 24 * 3600);
        cachePort.put("idempotency:key:" + booking.idempotencyKey(), confirmedBooking, 2 * 3600);

        auditPort.log("system", "BOOKING_CONFIRMED", "BOOKING", booking.id().toString(), booking, confirmedBooking);
        auditPort.log("system", "SEAT_RESERVED", "SEAT", seat.id().toString(), seat, savedSeat);
        auditPort.log("system", "PAYMENT_SUCCESS", "PAYMENT", payment.id().toString(), payment, successPayment);
        auditPort.log("system", "WEBHOOK_PROCESSED", "WEBHOOK", eventId, null, "Booking confirmed");
    }

    private void failBooking(Booking booking, Seat seat, Payment payment, String eventId) {
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

        webhookEventRepo.saveEvent("mock-payment", eventId, "", "PROCESSED", null);

        // Write-through cache updates
        cachePort.put("seat:cache:" + seat.id(), savedSeat, 24 * 3600);
        cachePort.evict("idempotency:key:" + booking.idempotencyKey());

        auditPort.log("system", "BOOKING_CANCELLED", "BOOKING", booking.id().toString(), booking, cancelledBooking);
        auditPort.log("system", "SEAT_RELEASED", "SEAT", seat.id().toString(), seat, savedSeat);
        auditPort.log("system", "PAYMENT_FAILED", "PAYMENT", payment.id().toString(), payment, failedPayment);
        auditPort.log("system", "WEBHOOK_PROCESSED", "WEBHOOK", eventId, null, "Booking cancelled due to payment failure");
    }

    private void handleLateArrival(Booking booking, Payment payment, String eventId) {
        auditPort.log("system", "WEBHOOK_LATE_ARRIVAL", "BOOKING", booking.id().toString(), booking, booking);

        Payment refundedPayment = new Payment(
            payment.id(), payment.bookingId(), payment.externalPaymentId(), payment.amount(),
            PaymentStatus.REFUNDED, payment.rawPayload(), payment.createdAt(), java.time.LocalDateTime.now()
        );
        paymentRepo.save(refundedPayment);

        webhookEventRepo.saveEvent("mock-payment", eventId, "", "PROCESSED", "Late arrival refund triggered");

        auditPort.log("system", "REFUND_INITIATED", "PAYMENT", payment.id().toString(), payment, refundedPayment);
        paymentGateway.refund(payment.externalPaymentId());
        
        auditPort.log("system", "REFUND_COMPLETED", "PAYMENT", payment.id().toString(), refundedPayment, refundedPayment);
        auditPort.log("system", "WEBHOOK_PROCESSED", "WEBHOOK", eventId, null, "Late arrival auto-refund completed");
    }
}
