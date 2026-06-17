package com.linkz.seatreservation.business.port.external;

public interface WebhookEventRepositoryPort {
    boolean existsByEventId(String eventId);
    void saveEvent(String provider, String eventId, String rawPayload, String status, String error);
}
