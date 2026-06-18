package com.linkz.seatreservation.business.service;

import com.linkz.seatreservation.business.domain.enums.*;
import com.linkz.seatreservation.business.domain.model.*;
import com.linkz.seatreservation.business.port.in.HandlePaymentNotificationUseCase;
import com.linkz.seatreservation.business.port.external.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;
import java.util.Map;

@Service
public class PaymentNotificationService implements HandlePaymentNotificationUseCase {
    private final BookingRepositoryPort bookingRepo;
    private final SeatRepositoryPort seatRepo;
    private final PaymentRepositoryPort paymentRepo;
    private final PaymentNotificationRepositoryPort paymentNotificationRepo;
    private final PaymentGatewayPort paymentGateway;
    private final CachePort cachePort;
    private final AuditPort auditPort;
    private final TransactionTemplate transactionTemplate;

    public PaymentNotificationService(BookingRepositoryPort bookingRepo,
                                      SeatRepositoryPort seatRepo,
                                      PaymentRepositoryPort paymentRepo,
                                      PaymentNotificationRepositoryPort paymentNotificationRepo,
                                      PaymentGatewayPort paymentGateway,
                                      CachePort cachePort,
                                      AuditPort auditPort,
                                      TransactionTemplate transactionTemplate) {
        this.bookingRepo = bookingRepo;
        this.seatRepo = seatRepo;
        this.paymentRepo = paymentRepo;
        this.paymentNotificationRepo = paymentNotificationRepo;
        this.paymentGateway = paymentGateway;
        this.cachePort = cachePort;
        this.auditPort = auditPort;
        this.transactionTemplate = transactionTemplate;
    }

    @Override
    public void handleNotification(PaymentNotificationCommand cmd) {
        // Step 1: Check if the event was already processed or marked duplicate
        java.util.Optional<String> existingStatus = paymentNotificationRepo.findStatusByEventId(cmd.eventId());
        if (existingStatus.isPresent()) {
            String status = existingStatus.get();
            if ("PROCESSED".equals(status) || "DUPLICATE".equals(status)) {
                auditPort.log("system", "WEBHOOK_DUPLICATE", "WEBHOOK", cmd.eventId(), null, 
                    auditDetails("Duplicate event ID checked", cmd.eventId(), cmd.rawPayload()));
                paymentNotificationRepo.saveEvent("mock-payment", cmd.eventId(), cmd.rawPayload(), "DUPLICATE", "Duplicate event detected");
                return;
            }
        }

        // Step 2: Persist raw payload to database IMMEDIATELY — before ANY logic.
        paymentNotificationRepo.saveEvent("mock-payment", cmd.eventId(), cmd.rawPayload(), "RECEIVED", null);
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
                            paymentNotificationRepo.saveEvent("mock-payment", cmd.eventId(), cmd.rawPayload(), "PROCESSED", null);
                            auditPort.log("system", "WEBHOOK_PROCESSED", "WEBHOOK", cmd.eventId(), null, 
                                auditDetails("Failed payment for already expired/cancelled booking", cmd.eventId(), cmd.rawPayload()));
                        }
                    }
                    case CONFIRMED -> {
                        Map<String, Object> dupDetails = auditDetails("booking", booking, cmd.eventId());
                        dupDetails.put("message", "Duplicate webhook for confirmed booking");
                        dupDetails.put("rawPayload", cmd.rawPayload());
                        auditPort.log("system", "WEBHOOK_DUPLICATE", "BOOKING", booking.id().toString(), booking, dupDetails);
                        paymentNotificationRepo.saveEvent("mock-payment", cmd.eventId(), cmd.rawPayload(), "PROCESSED", "Duplicate webhook for confirmed booking");
                    }
                }
            });
        } catch (Exception e) {
            paymentNotificationRepo.saveEvent("mock-payment", cmd.eventId(), cmd.rawPayload(), "FAILED", e.getMessage());
            throw e;
        }
    }

    private void confirmBooking(Booking booking, Seat seat, Payment payment, PaymentNotificationCommand cmd) {
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

        paymentNotificationRepo.saveEvent("mock-payment", cmd.eventId(), "", "PROCESSED", null);

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

        auditPort.log("system", "BOOKING_CONFIRMED", "BOOKING", booking.id().toString(), booking, auditDetails("booking", confirmedBooking, cmd.eventId()));
        auditPort.log("system", "SEAT_RESERVED", "SEAT", seat.id().toString(), seat, auditDetails("seat", savedSeat, cmd.eventId()));
        auditPort.log("system", "PAYMENT_SUCCESS", "PAYMENT", payment.id().toString(), payment, auditDetails("payment", successPayment, cmd.eventId()));
        auditPort.log("system", "WEBHOOK_PROCESSED", "WEBHOOK", cmd.eventId(), null, auditDetails("Booking confirmed", cmd.eventId(), cmd.rawPayload()));
    }

    private void failBooking(Booking booking, Seat seat, Payment payment, PaymentNotificationCommand cmd) {
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

        paymentNotificationRepo.saveEvent("mock-payment", cmd.eventId(), "", "PROCESSED", null);

        cachePort.put("seat:cache:" + seat.id(), savedSeat, 24 * 3600);

        auditPort.log("system", "BOOKING_CANCELLED", "BOOKING", booking.id().toString(), booking, auditDetails("booking", cancelledBooking, cmd.eventId()));
        auditPort.log("system", "SEAT_RELEASED", "SEAT", seat.id().toString(), seat, auditDetails("seat", savedSeat, cmd.eventId()));
        auditPort.log("system", "PAYMENT_FAILED", "PAYMENT", payment.id().toString(), payment, auditDetails("payment", failedPayment, cmd.eventId()));
        auditPort.log("system", "WEBHOOK_PROCESSED", "WEBHOOK", cmd.eventId(), null, auditDetails("Payment failed", cmd.eventId(), cmd.rawPayload()));
    }

    private void handleLateArrival(Booking booking, Payment payment, PaymentNotificationCommand cmd) {
        Payment refundedPayment = new Payment(
            payment.id(), payment.bookingId(), payment.externalPaymentId(), payment.amount(),
            PaymentStatus.REFUNDED, payment.rawPayload(), payment.createdAt(), java.time.LocalDateTime.now()
        );
        paymentRepo.save(refundedPayment);

        paymentNotificationRepo.saveEvent("mock-payment", cmd.eventId(), "", "PROCESSED", null);

        auditPort.log("system", "REFUND_INITIATED", "PAYMENT", payment.id().toString(), payment, auditDetails("refund_initiated", refundedPayment, cmd.eventId()));
        paymentGateway.refund(payment.externalPaymentId());
        auditPort.log("system", "REFUND_COMPLETED", "PAYMENT", payment.id().toString(), payment, auditDetails("refund_completed", refundedPayment, cmd.eventId()));
        auditPort.log("system", "WEBHOOK_PROCESSED", "WEBHOOK", cmd.eventId(), null, auditDetails("Late arrival payment refunded", cmd.eventId(), cmd.rawPayload()));
    }

    private Map<String, Object> auditDetails(String name, Object value, String eventId) {
        return new java.util.HashMap<>(Map.of(name, value, "triggerEventId", eventId));
    }
}
