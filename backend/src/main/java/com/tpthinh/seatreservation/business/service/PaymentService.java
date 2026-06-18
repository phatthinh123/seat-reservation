package com.tpthinh.seatreservation.business.service;

import com.tpthinh.seatreservation.business.domain.enums.BookingStatus;
import com.tpthinh.seatreservation.business.domain.enums.PaymentStatus;
import com.tpthinh.seatreservation.business.domain.exception.*;
import com.tpthinh.seatreservation.business.domain.model.Booking;
import com.tpthinh.seatreservation.business.domain.model.Payment;
import com.tpthinh.seatreservation.business.port.in.InitiatePaymentUseCase;
import com.tpthinh.seatreservation.business.port.external.*;
import org.springframework.context.ApplicationEventPublisher;
import com.tpthinh.seatreservation.business.domain.event.AuditEvents.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.UUID;

@Service
public class PaymentService implements InitiatePaymentUseCase {
    private final BookingRepositoryPort bookingRepo;
    private final PaymentRepositoryPort paymentRepo;
    private final PaymentGatewayPort paymentGateway;
    private final ApplicationEventPublisher eventPublisher;
    private final TransactionTemplate transactionTemplate;

    public PaymentService(BookingRepositoryPort bookingRepo,
                          PaymentRepositoryPort paymentRepo,
                          PaymentGatewayPort paymentGateway,
                          ApplicationEventPublisher eventPublisher,
                          TransactionTemplate transactionTemplate) {
        this.bookingRepo = bookingRepo;
        this.paymentRepo = paymentRepo;
        this.paymentGateway = paymentGateway;
        this.eventPublisher = eventPublisher;
        this.transactionTemplate = transactionTemplate;
    }

    @Override
    public Payment initiatePayment(InitiatePaymentCommand cmd) {
        Booking booking = bookingRepo.findById(cmd.bookingId())
            .orElseThrow(() -> new BookingNotFoundException("Booking not found"));

        if (!booking.userId().equals(cmd.userId())) {
            throw new BookingNotOwnedException("Booking does not belong to the user");
        }

        if (booking.status() != BookingStatus.PENDING) {
            if (booking.status() == BookingStatus.CONFIRMED) {
                throw new BookingAlreadyPaidException("Booking has already been paid");
            }
            throw new BookingExpiredException("Booking hold has expired or is invalid");
        }

        if (booking.holdExpiresAt().isBefore(LocalDateTime.now())) {
            throw new BookingExpiredException("Booking hold has expired");
        }

        BigDecimal amount = new BigDecimal("100.00");

        Payment pendingPayment = transactionTemplate.execute(status -> {
            Payment payment = new Payment(
                UUID.randomUUID(),
                booking.id(),
                null,
                amount,
                PaymentStatus.PENDING,
                null,
                LocalDateTime.now(),
                LocalDateTime.now()
            );
            Payment saved = paymentRepo.save(payment);
            eventPublisher.publishEvent(new PaymentInitiatedEvent(cmd.userId(), saved));
            return saved;
        });

        // Delegate to gateway — forward the simulateFail flag so the mock service
        // can decide whether to deliver a SUCCESS or FAILURE webhook.
        String externalPaymentId = paymentGateway.initiatePayment(booking.id(), amount, null, cmd.simulateFail(), cmd.simulateDelay());

        Payment updatedPayment = transactionTemplate.execute(status -> {
            Payment payment = paymentRepo.findById(pendingPayment.id())
                .orElseThrow(() -> new RuntimeException("Payment record not found"));
            Payment newPayment = new Payment(
                payment.id(),
                payment.bookingId(),
                externalPaymentId,
                payment.amount(),
                payment.status(),
                payment.rawPayload(),
                payment.createdAt(),
                LocalDateTime.now()
            );
            return paymentRepo.save(newPayment);
        });

        return updatedPayment;
    }
}
