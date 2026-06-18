package com.linkz.seatreservation.business.domain.event;

import com.linkz.seatreservation.business.domain.model.Booking;
import com.linkz.seatreservation.business.domain.model.Payment;
import com.linkz.seatreservation.business.domain.model.Seat;
import java.util.UUID;

public final class AuditEvents {
    private AuditEvents() {}

    public record SeatHeldEvent(Seat beforeState, Seat afterState) {}

    public record BookingCreatedEvent(String userId, Booking booking) {}

    public record PaymentInitiatedEvent(String userId, Payment payment) {}

    public record PaymentNotificationReceivedEvent(String eventId, String rawPayload) {}

    public record PaymentNotificationDuplicateEvent(String eventId, String rawPayload, String reason) {}

    public record BookingDuplicateNotificationEvent(Booking booking, String eventId, String rawPayload) {}

    public record BookingConfirmedEvent(
        Booking beforeState, 
        Booking afterState, 
        Seat seatBefore, 
        Seat seatAfter, 
        Payment paymentBefore, 
        Payment paymentAfter, 
        String triggerEventId
    ) {}

    public record BookingCancelledEvent(
        Booking beforeState, 
        Booking afterState, 
        Seat seatBefore, 
        Seat seatAfter, 
        Payment paymentBefore, 
        Payment paymentAfter, 
        String triggerEventId
    ) {}

    public record BookingExpiredEvent(
        Booking beforeState, 
        Booking afterState, 
        Seat seatBefore, 
        Seat seatAfter, 
        Payment paymentBefore, 
        Payment paymentAfter
    ) {}

    public record BookingLateRefundEvent(
        Booking booking, 
        Payment paymentBefore, 
        Payment paymentAfter, 
        String triggerEventId
    ) {}

    public record ManualReconcileEvent(UUID bookingId) {}

    public record PaymentNotificationProcessedEvent(String eventId, String rawPayload, String message) {}

    public record ReconciliationRunEvent() {}
}
