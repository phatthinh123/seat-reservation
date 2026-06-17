package com.linkz.seatreservation.business.port.in;

public interface HandleWebhookUseCase {
    void handleWebhook(WebhookEventCommand command);

    record WebhookEventCommand(String eventId, String paymentId, String bookingId, String status, String rawPayload) {}
}
