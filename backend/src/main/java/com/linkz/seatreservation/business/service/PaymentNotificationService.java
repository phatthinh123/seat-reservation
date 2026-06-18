package com.linkz.seatreservation.business.service;

import com.linkz.seatreservation.business.domain.enums.*;
import com.linkz.seatreservation.business.domain.model.*;
import com.linkz.seatreservation.business.port.in.HandlePaymentNotificationUseCase;
import com.linkz.seatreservation.business.port.external.*;
import com.linkz.seatreservation.business.domain.event.AuditEvents.*;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

@Service
public class PaymentNotificationService implements HandlePaymentNotificationUseCase {
    private final BookingRepositoryPort bookingRepo;
    private final SeatRepositoryPort seatRepo;
    private final PaymentRepositoryPort paymentRepo;
    private final PaymentNotificationRepositoryPort paymentNotificationRepo;
    private final PaymentGatewayPort paymentGateway;
    private final CachePort cachePort;
    private final ApplicationEventPublisher eventPublisher;
    private final TransactionTemplate transactionTemplate;

    public PaymentNotificationService(BookingRepositoryPort bookingRepo,
                                      SeatRepositoryPort seatRepo,
                                      PaymentRepositoryPort paymentRepo,
                                      PaymentNotificationRepositoryPort paymentNotificationRepo,
                                      PaymentGatewayPort paymentGateway,
                                      CachePort cachePort,
                                      ApplicationEventPublisher eventPublisher,
                                      TransactionTemplate transactionTemplate) {
        this.bookingRepo = bookingRepo;
        this.seatRepo = seatRepo;
        this.paymentRepo = paymentRepo;
        this.paymentNotificationRepo = paymentNotificationRepo;
        this.paymentGateway = paymentGateway;
        this.cachePort = cachePort;
        this.eventPublisher = eventPublisher;
        this.transactionTemplate = transactionTemplate;
    }

    @Override
    public void handleNotification(PaymentNotificationCommand cmd) {
        // Step 1: Check if the event was already processed or marked duplicate
        java.util.Optional<String> existingStatus = paymentNotificationRepo.findStatusByEventId(cmd.eventId());
        if (existingStatus.isPresent()) {
            String status = existingStatus.get();
            if ("PROCESSED".equals(status) || "DUPLICATE".equals(status)) {
                eventPublisher.publishEvent(new PaymentNotificationDuplicateEvent(cmd.eventId(), cmd.rawPayload(), "Duplicate event ID checked"));
                paymentNotificationRepo.saveEvent("mock-payment", cmd.eventId(), cmd.rawPayload(), "DUPLICATE", "Duplicate event detected");
                return;
            }
        }

        // Step 2: Persist raw payload to database IMMEDIATELY — before ANY logic.
        paymentNotificationRepo.saveEvent("mock-payment", cmd.eventId(), cmd.rawPayload(), "RECEIVED", null);
        eventPublisher.publishEvent(new PaymentNotificationReceivedEvent(cmd.eventId(), cmd.rawPayload()));

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
                            eventPublisher.publishEvent(new PaymentNotificationProcessedEvent(cmd.eventId(), cmd.rawPayload(), "Failed payment for already expired/cancelled booking"));
                        }
                    }
                    case CONFIRMED -> {
                        eventPublisher.publishEvent(new BookingDuplicateNotificationEvent(booking, cmd.eventId(), cmd.rawPayload()));
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
        Booking savedBooking = bookingRepo.save(confirmedBooking);

        Seat reservedSeat = new Seat(seat.id(), seat.label(), SeatStatus.RESERVED, seat.version());
        Seat savedSeat = seatRepo.save(reservedSeat);

        Payment successPayment = new Payment(
            payment.id(), payment.bookingId(), payment.externalPaymentId(), payment.amount(),
            PaymentStatus.SUCCESS, payment.rawPayload(), payment.createdAt(), java.time.LocalDateTime.now()
        );
        Payment savedPayment = paymentRepo.save(successPayment);

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

        eventPublisher.publishEvent(new BookingConfirmedEvent(
            booking, savedBooking,
            seat, savedSeat,
            payment, savedPayment,
            cmd.eventId()
        ));
    }

    private void failBooking(Booking booking, Seat seat, Payment payment, PaymentNotificationCommand cmd) {
        Booking cancelledBooking = new Booking(
            booking.id(), booking.userId(), booking.seatId(), BookingStatus.CANCELLED,
            booking.idempotencyKey(), booking.holdExpiresAt(), booking.createdAt(), java.time.LocalDateTime.now()
        );
        Booking savedBooking = bookingRepo.save(cancelledBooking);

        Seat availableSeat = new Seat(seat.id(), seat.label(), SeatStatus.AVAILABLE, seat.version());
        Seat savedSeat = seatRepo.save(availableSeat);

        Payment failedPayment = new Payment(
            payment.id(), payment.bookingId(), payment.externalPaymentId(), payment.amount(),
            PaymentStatus.FAILED, payment.rawPayload(), payment.createdAt(), java.time.LocalDateTime.now()
        );
        Payment savedPayment = paymentRepo.save(failedPayment);

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
        Payment refundedPayment = new Payment(
            payment.id(), payment.bookingId(), payment.externalPaymentId(), payment.amount(),
            PaymentStatus.REFUNDED, payment.rawPayload(), payment.createdAt(), java.time.LocalDateTime.now()
        );
        Payment savedPayment = paymentRepo.save(refundedPayment);

        paymentNotificationRepo.saveEvent("mock-payment", cmd.eventId(), "", "PROCESSED", null);

        eventPublisher.publishEvent(new BookingLateRefundEvent(booking, payment, savedPayment, cmd.eventId()));
        paymentGateway.refund(payment.externalPaymentId());
    }
}
