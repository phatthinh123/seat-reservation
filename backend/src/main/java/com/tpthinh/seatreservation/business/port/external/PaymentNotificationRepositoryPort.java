package com.tpthinh.seatreservation.business.port.external;

import java.util.Optional;

public interface PaymentNotificationRepositoryPort {
    boolean existsByEventId(String eventId);
    Optional<String> findStatusByEventId(String eventId);
    void saveEvent(String provider, String eventId, String rawPayload, String status, String error);
}
