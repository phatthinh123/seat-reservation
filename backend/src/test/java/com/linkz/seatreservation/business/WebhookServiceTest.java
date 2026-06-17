package com.linkz.seatreservation.business;

import com.linkz.seatreservation.business.domain.enums.*;
import com.linkz.seatreservation.business.domain.model.*;
import com.linkz.seatreservation.business.port.in.HandleWebhookUseCase;
import com.linkz.seatreservation.business.port.out.*;
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

class WebhookServiceTest {
    BookingRepositoryPort bookingRepo = mock(BookingRepositoryPort.class);
    SeatRepositoryPort seatRepo = mock(SeatRepositoryPort.class);
    PaymentRepositoryPort paymentRepo = mock(PaymentRepositoryPort.class);
    WebhookEventRepositoryPort webhookEventRepo = mock(WebhookEventRepositoryPort.class);
    PaymentGatewayPort paymentGateway = mock(PaymentGatewayPort.class);
    CachePort cache = mock(CachePort.class);
    AuditPort audit = mock(AuditPort.class);
    TransactionTemplate transactionTemplate = mock(TransactionTemplate.class);

    WebhookService service = new WebhookService(bookingRepo, seatRepo, paymentRepo, webhookEventRepo, paymentGateway, cache, audit, transactionTemplate);

    @BeforeEach
    void setUp() {
        reset(bookingRepo, seatRepo, paymentRepo, webhookEventRepo, paymentGateway, cache, audit, transactionTemplate);
        
        // Stub transactionTemplate to execute without result immediately
        doAnswer(invocation -> {
            java.util.function.Consumer<org.springframework.transaction.TransactionStatus> callback = invocation.getArgument(0);
            callback.accept(null);
            return null;
        }).when(transactionTemplate).executeWithoutResult(any());
    }

    @Test
    void handleWebhook_whenBookingPending_shouldConfirmBooking() {
        String eventId = "evt-123";
        String paymentId = "pay-123";
        UUID bookingId = UUID.randomUUID();
        UUID seatId = UUID.randomUUID();

        Booking booking = new Booking(bookingId, "user-1", seatId, BookingStatus.PENDING, "key-1", LocalDateTime.now().plusMinutes(10), LocalDateTime.now(), LocalDateTime.now());
        Seat seat = new Seat(seatId, "A1", SeatStatus.HELD, 0L);
        Payment payment = new Payment(UUID.randomUUID(), bookingId, paymentId, BigDecimal.TEN, PaymentStatus.PENDING, "{}", LocalDateTime.now(), LocalDateTime.now());

        when(webhookEventRepo.existsByEventId(eventId)).thenReturn(false);
        when(bookingRepo.findByExternalPaymentIdForUpdate(paymentId)).thenReturn(Optional.of(booking));
        when(seatRepo.findByIdForUpdate(seatId)).thenReturn(Optional.of(seat));
        when(paymentRepo.findByExternalPaymentId(paymentId)).thenReturn(Optional.of(payment));
        when(seatRepo.findById(seatId)).thenReturn(Optional.of(seat));

        HandleWebhookUseCase.WebhookEventCommand cmd = new HandleWebhookUseCase.WebhookEventCommand(
            eventId, paymentId, bookingId.toString(), "SUCCESS", "{}"
        );
        service.handleWebhook(cmd);

        verify(bookingRepo).save(argThat(b -> b.status() == BookingStatus.CONFIRMED));
        verify(seatRepo).save(argThat(s -> s.status() == SeatStatus.RESERVED));
        verify(paymentRepo).save(argThat(p -> p.status() == PaymentStatus.SUCCESS));
        verify(webhookEventRepo).saveEvent(eq("mock-payment"), eq(eventId), any(), eq("PROCESSED"), any());
        verify(cache).put(eq("seat:cache:" + seatId), any(), anyLong());
        verify(cache).put(eq("idempotency:key:key-1"), any(), anyLong());
    }

    @Test
    void handleWebhook_whenBookingExpired_shouldTriggerRefund() {
        String eventId = "evt-123";
        String paymentId = "pay-123";
        UUID bookingId = UUID.randomUUID();
        UUID seatId = UUID.randomUUID();

        Booking booking = new Booking(bookingId, "user-1", seatId, BookingStatus.EXPIRED, "key-1", LocalDateTime.now().minusMinutes(1), LocalDateTime.now().minusMinutes(11), LocalDateTime.now().minusMinutes(1));
        Seat seat = new Seat(seatId, "A1", SeatStatus.AVAILABLE, 0L);
        Payment payment = new Payment(UUID.randomUUID(), bookingId, paymentId, BigDecimal.TEN, PaymentStatus.PENDING, "{}", LocalDateTime.now(), LocalDateTime.now());

        when(webhookEventRepo.existsByEventId(eventId)).thenReturn(false);
        when(bookingRepo.findByExternalPaymentIdForUpdate(paymentId)).thenReturn(Optional.of(booking));
        when(seatRepo.findByIdForUpdate(seatId)).thenReturn(Optional.of(seat));
        when(paymentRepo.findByExternalPaymentId(paymentId)).thenReturn(Optional.of(payment));

        HandleWebhookUseCase.WebhookEventCommand cmd = new HandleWebhookUseCase.WebhookEventCommand(
            eventId, paymentId, bookingId.toString(), "SUCCESS", "{}"
        );
        service.handleWebhook(cmd);

        verify(bookingRepo, never()).save(any());
        verify(seatRepo, never()).save(any());
        verify(paymentRepo).save(argThat(p -> p.status() == PaymentStatus.REFUNDED));
        verify(paymentGateway).refund(paymentId);
        verify(webhookEventRepo).saveEvent(eq("mock-payment"), eq(eventId), any(), eq("PROCESSED"), any());
    }

    @Test
    void handleWebhook_whenBookingAlreadyConfirmed_shouldLogDuplicate() {
        String eventId = "evt-123";
        String paymentId = "pay-123";
        UUID bookingId = UUID.randomUUID();
        UUID seatId = UUID.randomUUID();

        Booking booking = new Booking(bookingId, "user-1", seatId, BookingStatus.CONFIRMED, "key-1", LocalDateTime.now().plusMinutes(10), LocalDateTime.now(), LocalDateTime.now());
        Seat seat = new Seat(seatId, "A1", SeatStatus.RESERVED, 0L);
        Payment payment = new Payment(UUID.randomUUID(), bookingId, paymentId, BigDecimal.TEN, PaymentStatus.SUCCESS, "{}", LocalDateTime.now(), LocalDateTime.now());

        when(webhookEventRepo.existsByEventId(eventId)).thenReturn(false);
        when(bookingRepo.findByExternalPaymentIdForUpdate(paymentId)).thenReturn(Optional.of(booking));
        when(seatRepo.findByIdForUpdate(seatId)).thenReturn(Optional.of(seat));
        when(paymentRepo.findByExternalPaymentId(paymentId)).thenReturn(Optional.of(payment));

        HandleWebhookUseCase.WebhookEventCommand cmd = new HandleWebhookUseCase.WebhookEventCommand(
            eventId, paymentId, bookingId.toString(), "SUCCESS", "{}"
        );
        service.handleWebhook(cmd);

        verify(bookingRepo, never()).save(any());
        verify(seatRepo, never()).save(any());
        verify(paymentRepo, never()).save(any());
        verify(webhookEventRepo).saveEvent(eq("mock-payment"), eq(eventId), any(), eq("PROCESSED"), eq("Duplicate webhook for confirmed booking"));
        verify(audit).log(any(), eq("WEBHOOK_DUPLICATE"), eq("BOOKING"), any(), any(), any());
    }

    @Test
    void handleWebhook_withDuplicateEventId_shouldSkipProcessing() {
        String eventId = "evt-123";
        HandleWebhookUseCase.WebhookEventCommand cmd = new HandleWebhookUseCase.WebhookEventCommand(
            eventId, "pay-123", UUID.randomUUID().toString(), "SUCCESS", "{}"
        );

        when(webhookEventRepo.existsByEventId(eventId)).thenReturn(true);

        service.handleWebhook(cmd);

        verify(bookingRepo, never()).findByExternalPaymentIdForUpdate(any());
        verify(webhookEventRepo).saveEvent(eq("mock-payment"), eq(eventId), any(), eq("DUPLICATE"), any());
        verify(audit).log(any(), eq("WEBHOOK_DUPLICATE"), eq("WEBHOOK"), eq(eventId), any(), any());
    }
}
