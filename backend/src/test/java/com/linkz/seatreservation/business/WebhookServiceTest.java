package com.linkz.seatreservation.business;

import com.linkz.seatreservation.business.domain.enums.*;
import com.linkz.seatreservation.business.domain.model.*;
import com.linkz.seatreservation.business.port.in.HandleWebhookUseCase;
import com.linkz.seatreservation.business.port.external.*;
import com.linkz.seatreservation.business.service.WebhookService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.Optional;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * Pure unit tests for WebhookService — no Spring context, all collaborators mocked.
 *
 * Tests cover the state-machine transitions triggered by incoming webhooks:
 *   PENDING  → CONFIRMED  (happy path — payment succeeded)
 *   EXPIRED  → refund     (non-happy — webhook arrived after hold expired)
 *   CONFIRMED → duplicate (non-happy — webhook arrives for already-confirmed booking)
 *   any      → duplicate  (non-happy — event ID already processed)
 *
 * All tests follow BDD structure:
 *   // Given — set up preconditions
 *   // When  — invoke the method under test
 *   // Then  — assert the expected outcome
 */
class WebhookServiceTest {

    BookingRepositoryPort bookingRepo = mock(BookingRepositoryPort.class);
    SeatRepositoryPort seatRepo = mock(SeatRepositoryPort.class);
    PaymentRepositoryPort paymentRepo = mock(PaymentRepositoryPort.class);
    WebhookEventRepositoryPort webhookEventRepo = mock(WebhookEventRepositoryPort.class);
    PaymentGatewayPort paymentGateway = mock(PaymentGatewayPort.class);
    CachePort cache = mock(CachePort.class);
    AuditPort audit = mock(AuditPort.class);
    TransactionTemplate transactionTemplate = mock(TransactionTemplate.class);

    WebhookService service = new WebhookService(
        bookingRepo, seatRepo, paymentRepo, webhookEventRepo,
        paymentGateway, cache, audit, transactionTemplate
    );

    @BeforeEach
    void setUp() {
        reset(bookingRepo, seatRepo, paymentRepo, webhookEventRepo, paymentGateway, cache, audit, transactionTemplate);
        // Stub TransactionTemplate to execute callback inline (synchronous in tests)
        doAnswer(invocation -> {
            java.util.function.Consumer<org.springframework.transaction.TransactionStatus> callback = invocation.getArgument(0);
            callback.accept(null);
            return null;
        }).when(transactionTemplate).executeWithoutResult(any());
    }

    // ─── Happy Path ───────────────────────────────────────────────────────────

    /**
     * Happy path (payment success):
     * When a SUCCESS webhook arrives for a PENDING booking,
     * the booking transitions to CONFIRMED, the seat to RESERVED,
     * and both the seat cache and idempotency cache are updated.
     */
    @Test
    void testHandleWebhook_paymentSucceededForPendingBooking_shouldConfirmBookingAndReserveSeat() {
        // Given — a booking in PENDING state with a held seat and pending payment
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

        when(webhookEventRepo.findStatusByEventId(eventId)).thenReturn(Optional.empty());
        when(bookingRepo.findByExternalPaymentIdForUpdate(paymentId)).thenReturn(Optional.of(pendingBooking));
        when(seatRepo.findByIdForUpdate(seatId)).thenReturn(Optional.of(heldSeat));
        when(paymentRepo.findByExternalPaymentId(paymentId)).thenReturn(Optional.of(pendingPayment));
        when(seatRepo.findById(seatId)).thenReturn(Optional.of(heldSeat));

        // When — the SUCCESS webhook arrives
        service.handleWebhook(new HandleWebhookUseCase.WebhookEventCommand(
            eventId, paymentId, bookingId.toString(), "SUCCESS", "{}"
        ));

        // Then — booking CONFIRMED, seat RESERVED, payment SUCCESS, caches updated
        verify(bookingRepo).save(argThat(b -> b.status() == BookingStatus.CONFIRMED));
        verify(seatRepo).save(argThat(s -> s.status() == SeatStatus.RESERVED));
        verify(paymentRepo).save(argThat(p -> p.status() == PaymentStatus.SUCCESS));
        verify(webhookEventRepo).saveEvent(eq("mock-payment"), eq(eventId), any(), eq("PROCESSED"), any());
        verify(cache).put(eq("seat:cache:" + seatId), any(), anyLong());
        verify(cache).put(eq("idempotency:key:key-1"), any(), anyLong());
    }

    // ─── Non-Happy Path ───────────────────────────────────────────────────────

    /**
     * Non-happy path (late webhook after expiry):
     * When a SUCCESS webhook arrives but the booking is already EXPIRED
     * (hold timeout occurred before payment confirmed), the system must
     * auto-refund the payment — not confirm the booking — and release the seat.
     */
    @Test
    void testHandleWebhook_paymentSucceededButBookingAlreadyExpired_shouldTriggerAutoRefund() {
        // Given — booking has EXPIRED and seat is already AVAILABLE (hold released)
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

        when(webhookEventRepo.findStatusByEventId(eventId)).thenReturn(Optional.empty());
        when(bookingRepo.findByExternalPaymentIdForUpdate(paymentId)).thenReturn(Optional.of(expiredBooking));
        when(seatRepo.findByIdForUpdate(seatId)).thenReturn(Optional.of(availableSeat));
        when(paymentRepo.findByExternalPaymentId(paymentId)).thenReturn(Optional.of(pendingPayment));

        // When — the late SUCCESS webhook arrives
        service.handleWebhook(new HandleWebhookUseCase.WebhookEventCommand(
            eventId, paymentId, bookingId.toString(), "SUCCESS", "{}"
        ));

        // Then — refund is initiated, booking is NOT confirmed, seat is NOT changed
        verify(paymentGateway).refund(paymentId);
        verify(paymentRepo).save(argThat(p -> p.status() == PaymentStatus.REFUNDED));
        verify(bookingRepo, never()).save(any());
        verify(seatRepo, never()).save(any());
        verify(webhookEventRepo).saveEvent(eq("mock-payment"), eq(eventId), any(), eq("PROCESSED"), any());
    }

    /**
     * Non-happy path (already confirmed booking):
     * When a webhook arrives for a booking that is already CONFIRMED
     * (could happen if the reconciliation job confirmed it first),
     * the system must do nothing to state and log a WEBHOOK_DUPLICATE audit event.
     */
    @Test
    void testHandleWebhook_bookingAlreadyConfirmed_shouldLogDuplicateAndSkipStateChange() {
        // Given — booking is already CONFIRMED (seat already RESERVED)
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

        when(webhookEventRepo.findStatusByEventId(eventId)).thenReturn(Optional.empty());
        when(bookingRepo.findByExternalPaymentIdForUpdate(paymentId)).thenReturn(Optional.of(confirmedBooking));
        when(seatRepo.findByIdForUpdate(seatId)).thenReturn(Optional.of(reservedSeat));
        when(paymentRepo.findByExternalPaymentId(paymentId)).thenReturn(Optional.of(successPayment));

        // When — a duplicate webhook arrives for the already-confirmed booking
        service.handleWebhook(new HandleWebhookUseCase.WebhookEventCommand(
            eventId, paymentId, bookingId.toString(), "SUCCESS", "{}"
        ));

        // Then — no state changes; WEBHOOK_DUPLICATE is audited
        verify(bookingRepo, never()).save(any());
        verify(seatRepo, never()).save(any());
        verify(paymentRepo, never()).save(any());
        verify(webhookEventRepo).saveEvent(
            eq("mock-payment"), eq(eventId), any(), eq("PROCESSED"),
            eq("Duplicate webhook for confirmed booking")
        );
        verify(audit).log(any(), eq("WEBHOOK_DUPLICATE"), eq("BOOKING"), any(), any(), any());
    }

    /**
     * Non-happy path (duplicate event ID):
     * When a webhook event ID has already been recorded in the webhook_events table,
     * the entire business processing pipeline must be bypassed — no booking or seat
     * lookups happen — and a WEBHOOK_DUPLICATE audit entry is logged.
     */
    @Test
    void testHandleWebhook_duplicateEventId_shouldSkipProcessingEntirely() {
        // Given — this event ID already exists in webhook_events (processed previously)
        String eventId = "evt-already-processed";

        when(webhookEventRepo.findStatusByEventId(eventId)).thenReturn(Optional.of("PROCESSED"));

        // When — the duplicate event arrives
        service.handleWebhook(new HandleWebhookUseCase.WebhookEventCommand(
            eventId, "pay-123", UUID.randomUUID().toString(), "SUCCESS", "{}"
        ));

        // Then — no booking or seat lookups occur; DUPLICATE is saved and audited
        verify(bookingRepo, never()).findByExternalPaymentIdForUpdate(any());
        verify(webhookEventRepo).saveEvent(eq("mock-payment"), eq(eventId), any(), eq("DUPLICATE"), any());
        verify(audit).log(any(), eq("WEBHOOK_DUPLICATE"), eq("WEBHOOK"), eq(eventId), any(), any());
    }
}
