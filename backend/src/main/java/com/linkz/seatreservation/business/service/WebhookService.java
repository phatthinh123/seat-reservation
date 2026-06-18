package com.linkz.seatreservation.business.service;

import com.linkz.seatreservation.business.domain.enums.*;
import com.linkz.seatreservation.business.domain.model.*;
import com.linkz.seatreservation.business.port.in.HandleWebhookUseCase;
import com.linkz.seatreservation.business.port.external.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;
import java.util.Map;

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
        // Step 1: Check if the event was already processed or marked duplicate
        java.util.Optional<String> existingStatus = webhookEventRepo.findStatusByEventId(cmd.eventId());
        if (existingStatus.isPresent()) {
            String status = existingStatus.get();
            if ("PROCESSED".equals(status) || "DUPLICATE".equals(status)) {
                auditPort.log("system", "WEBHOOK_DUPLICATE", "WEBHOOK", cmd.eventId(), null, 
                    auditDetails("Duplicate event ID checked", cmd.eventId(), cmd.rawPayload()));
                webhookEventRepo.saveEvent("mock-payment", cmd.eventId(), cmd.rawPayload(), "DUPLICATE", "Duplicate event detected");
                return;
            }
        }

        // Step 2: Persist raw payload to webhook_events IMMEDIATELY — before ANY logic.
        // This is the "persist-first" invariant: if the process crashes at any point after
        // this line, the raw event is still on disk and can be replayed during reconciliation.
        webhookEventRepo.saveEvent("mock-payment", cmd.eventId(), cmd.rawPayload(), "RECEIVED", null);
        auditPort.log("system", "WEBHOOK_RECEIVED", "WEBHOOK", cmd.eventId(), null, cmd.rawPayload());

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
                            confirmBooking(booking, seat, payment, cmd);
                        } else {
                            failBooking(booking, seat, payment, cmd);
                        }
                    }
                    case EXPIRED, CANCELLED -> {
                        if ("SUCCESS".equalsIgnoreCase(cmd.status())) {
                            handleLateArrival(booking, payment, cmd);
                        } else {
                            webhookEventRepo.saveEvent("mock-payment", cmd.eventId(), cmd.rawPayload(), "PROCESSED", null);
                            auditPort.log("system", "WEBHOOK_PROCESSED", "WEBHOOK", cmd.eventId(), null, 
                                auditDetails("Failed payment for already expired/cancelled booking", cmd.eventId(), cmd.rawPayload()));
                        }
                    }
                    case CONFIRMED -> {
                        Map<String, Object> dupDetails = auditDetails("booking", booking, cmd.eventId());
                        dupDetails.put("message", "Duplicate webhook for confirmed booking");
                        dupDetails.put("rawPayload", cmd.rawPayload());
                        auditPort.log("system", "WEBHOOK_DUPLICATE", "BOOKING", booking.id().toString(), booking, dupDetails);
                        webhookEventRepo.saveEvent("mock-payment", cmd.eventId(), cmd.rawPayload(), "PROCESSED", "Duplicate webhook for confirmed booking");
                    }
                }
            });
        } catch (Exception e) {
            webhookEventRepo.saveEvent("mock-payment", cmd.eventId(), cmd.rawPayload(), "FAILED", e.getMessage());
            throw e;
        }
    }

    private void confirmBooking(Booking booking, Seat seat, Payment payment, WebhookEventCommand cmd) {
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

        webhookEventRepo.saveEvent("mock-payment", cmd.eventId(), "", "PROCESSED", null);

        // Write-through cache updates
        cachePort.put("seat:cache:" + seat.id(), savedSeat, 24 * 3600);
        cachePort.put("idempotency:key:" + booking.idempotencyKey(), confirmedBooking, 2 * 3600);

        auditPort.log("system", "BOOKING_CONFIRMED", "BOOKING", booking.id().toString(), booking, auditDetails("booking", confirmedBooking, cmd.eventId()));
        auditPort.log("system", "SEAT_RESERVED", "SEAT", seat.id().toString(), seat, auditDetails("seat", savedSeat, cmd.eventId()));
        auditPort.log("system", "PAYMENT_SUCCESS", "PAYMENT", payment.id().toString(), payment, auditDetails("payment", successPayment, cmd.eventId()));
        auditPort.log("system", "WEBHOOK_PROCESSED", "WEBHOOK", cmd.eventId(), null, auditDetails("Booking confirmed", cmd.eventId(), cmd.rawPayload()));
    }

    private void failBooking(Booking booking, Seat seat, Payment payment, WebhookEventCommand cmd) {
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

        webhookEventRepo.saveEvent("mock-payment", cmd.eventId(), "", "PROCESSED", null);

        // Write-through cache updates
        cachePort.put("seat:cache:" + seat.id(), savedSeat, 24 * 3600);
        cachePort.evict("idempotency:key:" + booking.idempotencyKey());

        auditPort.log("system", "BOOKING_CANCELLED", "BOOKING", booking.id().toString(), booking, auditDetails("booking", cancelledBooking, cmd.eventId()));
        auditPort.log("system", "SEAT_RELEASED", "SEAT", seat.id().toString(), seat, auditDetails("seat", savedSeat, cmd.eventId()));
        auditPort.log("system", "PAYMENT_FAILED", "PAYMENT", payment.id().toString(), payment, auditDetails("payment", failedPayment, cmd.eventId()));
        auditPort.log("system", "WEBHOOK_PROCESSED", "WEBHOOK", cmd.eventId(), null, auditDetails("Booking cancelled due to payment failure", cmd.eventId(), cmd.rawPayload()));
    }

    private void handleLateArrival(Booking booking, Payment payment, WebhookEventCommand cmd) {
        auditPort.log("system", "WEBHOOK_LATE_ARRIVAL", "BOOKING", booking.id().toString(), booking, auditDetails("booking", booking, cmd.eventId()));

        Payment refundedPayment = new Payment(
            payment.id(), payment.bookingId(), payment.externalPaymentId(), payment.amount(),
            PaymentStatus.REFUNDED, payment.rawPayload(), payment.createdAt(), java.time.LocalDateTime.now()
        );
        paymentRepo.save(refundedPayment);

        webhookEventRepo.saveEvent("mock-payment", cmd.eventId(), "", "PROCESSED", "Late arrival refund triggered");

        auditPort.log("system", "REFUND_INITIATED", "PAYMENT", payment.id().toString(), payment, auditDetails("payment", refundedPayment, cmd.eventId()));
        paymentGateway.refund(payment.externalPaymentId());
        
        auditPort.log("system", "REFUND_COMPLETED", "PAYMENT", payment.id().toString(), refundedPayment, auditDetails("payment", refundedPayment, cmd.eventId()));
        auditPort.log("system", "WEBHOOK_PROCESSED", "WEBHOOK", cmd.eventId(), null, auditDetails("Late arrival auto-refund completed", cmd.eventId(), cmd.rawPayload()));
    }

    private Map<String, Object> auditDetails(String message, String eventId, String rawPayload) {
        Map<String, Object> details = new java.util.HashMap<>();
        details.put("message", message);
        details.put("eventId", eventId);
        details.put("rawPayload", rawPayload);
        return details;
    }

    private Map<String, Object> auditDetails(Object dataKey, Object dataValue, String eventId) {
        Map<String, Object> details = new java.util.HashMap<>();
        details.put(String.valueOf(dataKey), dataValue);
        details.put("triggerEventId", eventId);
        return details;
    }
}
