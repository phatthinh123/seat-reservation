package com.tpthinh.seatreservation.business.port.in;

public interface HandlePaymentNotificationUseCase {
    void handleNotification(PaymentNotificationCommand command);

    record PaymentNotificationCommand(
        String eventId,
        String paymentId,
        String bookingId,
        String status,
        String rawPayload
    ) {}
}
