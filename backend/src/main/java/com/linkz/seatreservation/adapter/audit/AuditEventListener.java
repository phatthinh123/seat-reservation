package com.linkz.seatreservation.adapter.audit;

import com.linkz.seatreservation.business.domain.event.AuditEvents.*;
import com.linkz.seatreservation.business.port.external.AuditPort;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;
import java.util.Map;

@Component
public class AuditEventListener {
    private final AuditPort auditPort;

    public AuditEventListener(AuditPort auditPort) {
        this.auditPort = auditPort;
    }

    @EventListener
    public void onSeatHeld(SeatHeldEvent event) {
        auditPort.log(
            "system",
            "SEAT_HELD",
            "SEAT",
            event.afterState().id().toString(),
            event.beforeState(),
            event.afterState()
        );
    }

    @EventListener
    public void onBookingCreated(BookingCreatedEvent event) {
        auditPort.log(
            event.userId(),
            "BOOKING_CREATED",
            "BOOKING",
            event.booking().id().toString(),
            null,
            event.booking()
        );
    }

    @EventListener
    public void onPaymentInitiated(PaymentInitiatedEvent event) {
        auditPort.log(
            event.userId(),
            "PAYMENT_INITIATED",
            "PAYMENT",
            event.payment().id().toString(),
            null,
            event.payment()
        );
    }

    @EventListener
    public void onPaymentNotificationReceived(PaymentNotificationReceivedEvent event) {
        auditPort.log(
            "system",
            "WEBHOOK_RECEIVED",
            "WEBHOOK",
            event.eventId(),
            null,
            event.rawPayload()
        );
    }

    @EventListener
    public void onPaymentNotificationDuplicate(PaymentNotificationDuplicateEvent event) {
        auditPort.log(
            "system",
            "WEBHOOK_DUPLICATE",
            "WEBHOOK",
            event.eventId(),
            null,
            Map.of("message", event.reason(), "eventId", event.eventId(), "rawPayload", event.rawPayload())
        );
    }

    @EventListener
    public void onBookingDuplicateNotification(BookingDuplicateNotificationEvent event) {
        Map<String, Object> dupDetails = new java.util.HashMap<>();
        dupDetails.put("booking", event.booking());
        dupDetails.put("triggerEventId", event.eventId());
        dupDetails.put("message", "Duplicate webhook for confirmed booking");
        dupDetails.put("rawPayload", event.rawPayload());
        
        auditPort.log(
            "system",
            "WEBHOOK_DUPLICATE",
            "BOOKING",
            event.booking().id().toString(),
            event.booking(),
            dupDetails
        );
    }

    @EventListener
    public void onBookingConfirmed(BookingConfirmedEvent event) {
        String eventId = event.triggerEventId();
        
        auditPort.log(
            "system",
            "BOOKING_CONFIRMED",
            "BOOKING",
            event.afterState().id().toString(),
            event.beforeState(),
            auditDetails("booking", event.afterState(), eventId)
        );

        auditPort.log(
            "system",
            "SEAT_RESERVED",
            "SEAT",
            event.seatAfter().id().toString(),
            event.seatBefore(),
            auditDetails("seat", event.seatAfter(), eventId)
        );

        auditPort.log(
            "system",
            "PAYMENT_SUCCESS",
            "PAYMENT",
            event.paymentAfter().id().toString(),
            event.paymentBefore(),
            auditDetails("payment", event.paymentAfter(), eventId)
        );

        if (eventId != null && !eventId.isBlank()) {
            auditPort.log(
                "system",
                "WEBHOOK_PROCESSED",
                "WEBHOOK",
                eventId,
                null,
                auditDetails("Booking confirmed", eventId, "{}")
            );
        }
    }

    @EventListener
    public void onBookingCancelled(BookingCancelledEvent event) {
        String eventId = event.triggerEventId();

        auditPort.log(
            "system",
            "BOOKING_CANCELLED",
            "BOOKING",
            event.afterState().id().toString(),
            event.beforeState(),
            auditDetails("booking", event.afterState(), eventId)
        );

        auditPort.log(
            "system",
            "SEAT_RELEASED",
            "SEAT",
            event.seatAfter().id().toString(),
            event.seatBefore(),
            auditDetails("seat", event.seatAfter(), eventId)
        );

        auditPort.log(
            "system",
            "PAYMENT_FAILED",
            "PAYMENT",
            event.paymentAfter().id().toString(),
            event.paymentBefore(),
            auditDetails("payment", event.paymentAfter(), eventId)
        );

        if (eventId != null && !eventId.isBlank()) {
            auditPort.log(
                "system",
                "WEBHOOK_PROCESSED",
                "WEBHOOK",
                eventId,
                null,
                auditDetails("Payment failed", eventId, "{}")
            );
        }
    }

    @EventListener
    public void onBookingExpired(BookingExpiredEvent event) {
        auditPort.log(
            "system",
            "BOOKING_EXPIRED",
            "BOOKING",
            event.afterState().id().toString(),
            event.beforeState(),
            event.afterState()
        );

        auditPort.log(
            "system",
            "SEAT_RELEASED",
            "SEAT",
            event.seatAfter().id().toString(),
            event.seatBefore(),
            event.seatAfter()
        );

        if (event.paymentAfter() != null) {
            auditPort.log(
                "system",
                "PAYMENT_FAILED",
                "PAYMENT",
                event.paymentAfter().id().toString(),
                event.paymentBefore(),
                event.paymentAfter()
            );
        }
    }

    @EventListener
    public void onBookingLateRefund(BookingLateRefundEvent event) {
        String eventId = event.triggerEventId();

        auditPort.log(
            "system",
            "REFUND_INITIATED",
            "PAYMENT",
            event.paymentAfter().id().toString(),
            event.paymentBefore(),
            auditDetails("refund_initiated", event.paymentAfter(), eventId)
        );

        auditPort.log(
            "system",
            "REFUND_COMPLETED",
            "PAYMENT",
            event.paymentAfter().id().toString(),
            event.paymentBefore(),
            auditDetails("refund_completed", event.paymentAfter(), eventId)
        );

        if (eventId != null && !eventId.isBlank()) {
            auditPort.log(
                "system",
                "WEBHOOK_PROCESSED",
                "WEBHOOK",
                eventId,
                null,
                auditDetails("Late arrival payment refunded", eventId, "{}")
            );
        }
    }

    @EventListener
    public void onManualReconcile(ManualReconcileEvent event) {
        auditPort.log(
            "admin",
            "MANUAL_RECONCILE",
            "BOOKING",
            event.bookingId().toString(),
            null,
            "Manual reconcile triggered"
        );
    }

    @EventListener
    public void onReconciliationRun(ReconciliationRunEvent event) {
        auditPort.log(
            "system",
            "RECONCILIATION_RUN",
            "SYSTEM",
            "reconciliation",
            null,
            "Scheduled reconciliation run"
        );
    }

    @EventListener
    public void onPaymentNotificationProcessed(PaymentNotificationProcessedEvent event) {
        auditPort.log(
            "system",
            "WEBHOOK_PROCESSED",
            "WEBHOOK",
            event.eventId(),
            null,
            auditDetails("webhook", event.message(), event.eventId())
        );
    }

    private Map<String, Object> auditDetails(String name, Object value, String eventId) {
        return new java.util.HashMap<>(Map.of(name, value, "triggerEventId", eventId != null ? eventId : ""));
    }
}
