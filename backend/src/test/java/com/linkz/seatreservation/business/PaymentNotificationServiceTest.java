package com.linkz.seatreservation.business;

import com.linkz.seatreservation.business.domain.enums.*;
import com.linkz.seatreservation.business.domain.model.*;
import com.linkz.seatreservation.business.port.in.HandlePaymentNotificationUseCase;
import com.linkz.seatreservation.business.port.external.*;
import com.linkz.seatreservation.business.service.PaymentNotificationService;
import com.linkz.seatreservation.business.domain.event.AuditEvents.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.Optional;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * Pure unit tests for PaymentNotificationService - no Spring context, no I/O, all collaborators mocked.
 */
class PaymentNotificationServiceTest {

    BookingRepositoryPort bookingRepo = mock(BookingRepositoryPort.class);
    SeatRepositoryPort seatRepo = mock(SeatRepositoryPort.class);
    PaymentRepositoryPort paymentRepo = mock(PaymentRepositoryPort.class);
    PaymentNotificationRepositoryPort paymentNotificationRepo = mock(PaymentNotificationRepositoryPort.class);
    PaymentGatewayPort paymentGateway = mock(PaymentGatewayPort.class);
    CachePort cache = mock(CachePort.class);
    ApplicationEventPublisher eventPublisher = mock(ApplicationEventPublisher.class);
    TransactionTemplate transactionTemplate = mock(TransactionTemplate.class);

    PaymentNotificationService service = new PaymentNotificationService(
        bookingRepo, seatRepo, paymentRepo, paymentNotificationRepo,
        paymentGateway, cache, eventPublisher, transactionTemplate
    );

    @BeforeEach
    void setUp() {
        reset(bookingRepo, seatRepo, paymentRepo, paymentNotificationRepo, paymentGateway, cache, eventPublisher, transactionTemplate);
        // Stub TransactionTemplate to execute callback inline (synchronous in tests)
        doAnswer(invocation -> {
            java.util.function.Consumer<org.springframework.transaction.TransactionStatus> callback = invocation.getArgument(0);
            callback.accept(null);
            return null;
        }).when(transactionTemplate).executeWithoutResult(any());

        when(transactionTemplate.execute(any())).thenAnswer(invocation -> {
            org.springframework.transaction.support.TransactionCallback<?> callback = invocation.getArgument(0);
            return callback.doInTransaction(null);
        });

        // Stub repositories to return the saved entities (preventing nulls)
        when(seatRepo.save(any(Seat.class))).thenAnswer(inv -> inv.getArgument(0));
        when(bookingRepo.save(any(Booking.class))).thenAnswer(inv -> inv.getArgument(0));
        when(paymentRepo.save(any(Payment.class))).thenAnswer(inv -> inv.getArgument(0));
    }

    // ─── Happy Path ───────────────────────────────────────────────────────────

    @Test
    void testHandleWebhook_paymentSucceededForPendingBooking_shouldConfirmBookingAndReserveSeat() {
        String eventId = "evt-success";
        String paymentId = "pay-success";
        UUID bookingId = UUID.randomUUID();
        UUID seatId = UUID.randomUUID();

        Booking pendingBooking = new Booking(
            bookingId, "user-1", seatId, BookingStatus.PENDING,
            "key-1", LocalDateTime.now().plusMinutes(10),
            LocalDateTime.now(), LocalDateTime.now()
        );
        Seat heldSeat = new Seat(seatId, "A1", SeatStatus.HELD, 0L);
        Payment pendingPayment = new Payment(
            UUID.randomUUID(), bookingId, paymentId, BigDecimal.TEN,
            PaymentStatus.PENDING, "{}", LocalDateTime.now(), LocalDateTime.now()
        );

        when(paymentNotificationRepo.findStatusByEventId(eventId)).thenReturn(Optional.empty());
        when(bookingRepo.findByExternalPaymentIdForUpdate(paymentId)).thenReturn(Optional.of(pendingBooking));
        when(seatRepo.findByIdForUpdate(seatId)).thenReturn(Optional.of(heldSeat));
        when(paymentRepo.findByExternalPaymentId(paymentId)).thenReturn(Optional.of(pendingPayment));
        when(seatRepo.findById(seatId)).thenReturn(Optional.of(heldSeat));

        service.handleNotification(new HandlePaymentNotificationUseCase.PaymentNotificationCommand(
            eventId, paymentId, bookingId.toString(), "SUCCESS", "{}"
        ));

        verify(bookingRepo).save(argThat(b -> b.status() == BookingStatus.CONFIRMED));
        verify(seatRepo).save(argThat(s -> s.status() == SeatStatus.RESERVED));
        verify(paymentRepo).save(argThat(p -> p.status() == PaymentStatus.SUCCESS));
        verify(paymentNotificationRepo).saveEvent(eq("mock-payment"), eq(eventId), any(), eq("PROCESSED"), any());
        verify(cache).put(eq("seat:cache:" + seatId), any(), anyLong());
        verify(eventPublisher).publishEvent(any(BookingConfirmedEvent.class));
    }

    // ─── Non-Happy Path ───────────────────────────────────────────────────────

    @Test
    void testHandleWebhook_paymentSucceededButBookingAlreadyExpired_shouldTriggerAutoRefund() {
        String eventId = "evt-late";
        String paymentId = "pay-late";
        UUID bookingId = UUID.randomUUID();
        UUID seatId = UUID.randomUUID();

        Booking expiredBooking = new Booking(
            bookingId, "user-1", seatId, BookingStatus.EXPIRED,
            "key-1", LocalDateTime.now().minusMinutes(1),
            LocalDateTime.now().minusMinutes(11), LocalDateTime.now().minusMinutes(1)
        );
        Seat availableSeat = new Seat(seatId, "A1", SeatStatus.AVAILABLE, 0L);
        Payment pendingPayment = new Payment(
            UUID.randomUUID(), bookingId, paymentId, BigDecimal.TEN,
            PaymentStatus.PENDING, "{}", LocalDateTime.now(), LocalDateTime.now()
        );

        when(paymentNotificationRepo.findStatusByEventId(eventId)).thenReturn(Optional.empty());
        when(bookingRepo.findByExternalPaymentIdForUpdate(paymentId)).thenReturn(Optional.of(expiredBooking));
        when(seatRepo.findByIdForUpdate(seatId)).thenReturn(Optional.of(availableSeat));
        when(paymentRepo.findByExternalPaymentId(paymentId)).thenReturn(Optional.of(pendingPayment));

        service.handleNotification(new HandlePaymentNotificationUseCase.PaymentNotificationCommand(
            eventId, paymentId, bookingId.toString(), "SUCCESS", "{}"
        ));

        verify(paymentGateway).refund(eq(paymentId));
        verify(paymentRepo).save(argThat(p -> p.status() == PaymentStatus.REFUNDED));
        verify(bookingRepo, never()).save(any());
        verify(seatRepo, never()).save(any());
        verify(paymentNotificationRepo).saveEvent(eq("mock-payment"), eq(eventId), any(), eq("PROCESSED"), any());
        verify(eventPublisher).publishEvent(any(BookingLateRefundEvent.class));
    }

    @Test
    void testHandleWebhook_bookingAlreadyConfirmed_shouldLogDuplicateAndSkipStateChange() {
        String eventId = "evt-already-done";
        String paymentId = "pay-done";
        UUID bookingId = UUID.randomUUID();
        UUID seatId = UUID.randomUUID();

        Booking confirmedBooking = new Booking(
            bookingId, "user-1", seatId, BookingStatus.CONFIRMED,
            "key-1", LocalDateTime.now().plusMinutes(10),
            LocalDateTime.now(), LocalDateTime.now()
        );
        Seat reservedSeat = new Seat(seatId, "A1", SeatStatus.RESERVED, 0L);
        Payment successPayment = new Payment(
            UUID.randomUUID(), bookingId, paymentId, BigDecimal.TEN,
            PaymentStatus.SUCCESS, "{}", LocalDateTime.now(), LocalDateTime.now()
        );

        when(paymentNotificationRepo.findStatusByEventId(eventId)).thenReturn(Optional.empty());
        when(bookingRepo.findByExternalPaymentIdForUpdate(paymentId)).thenReturn(Optional.of(confirmedBooking));
        when(seatRepo.findByIdForUpdate(seatId)).thenReturn(Optional.of(reservedSeat));
        when(paymentRepo.findByExternalPaymentId(paymentId)).thenReturn(Optional.of(successPayment));

        service.handleNotification(new HandlePaymentNotificationUseCase.PaymentNotificationCommand(
            eventId, paymentId, bookingId.toString(), "SUCCESS", "{}"
        ));

        verify(bookingRepo, never()).save(any());
        verify(seatRepo, never()).save(any());
        verify(paymentRepo, never()).save(any());
        verify(paymentNotificationRepo).saveEvent(
            eq("mock-payment"), eq(eventId), any(), eq("PROCESSED"),
            eq("Duplicate webhook for confirmed booking")
        );
        verify(eventPublisher).publishEvent(any(BookingDuplicateNotificationEvent.class));
    }

    @Test
    void testHandleWebhook_duplicateEventId_shouldSkipProcessingEntirely() {
        String eventId = "evt-already-processed";

        when(paymentNotificationRepo.findStatusByEventId(eventId)).thenReturn(Optional.of("PROCESSED"));

        service.handleNotification(new HandlePaymentNotificationUseCase.PaymentNotificationCommand(
            eventId, "pay-123", UUID.randomUUID().toString(), "SUCCESS", "{}"
        ));

        verify(bookingRepo, never()).findByExternalPaymentIdForUpdate(any());
        verify(paymentNotificationRepo).saveEvent(eq("mock-payment"), eq(eventId), any(), eq("DUPLICATE"), any());
        verify(eventPublisher).publishEvent(any(PaymentNotificationDuplicateEvent.class));
    }
}
