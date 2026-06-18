package com.linkz.seatreservation.business.port.external;

import java.util.Optional;

public interface WebhookEventRepositoryPort {
    boolean existsByEventId(String eventId);
    Optional<String> findStatusByEventId(String eventId);
    void saveEvent(String provider, String eventId, String rawPayload, String status, String error);
}
