package com.linkz.seatreservation.web.dto;

import com.linkz.seatreservation.business.domain.model.AuditEntry;
import java.time.LocalDateTime;
import java.util.UUID;

public record AuditLogDto(
    UUID id,
    String actor,
    String action,
    String entityType,
    String entityId,
    String beforeState,
    String afterState,
    LocalDateTime createdAt
) {
    public static AuditLogDto from(AuditEntry entry) {
        if (entry == null) return null;
        return new AuditLogDto(
            entry.id(),
            entry.actor(),
            entry.action(),
            entry.entityType(),
            entry.entityId(),
            entry.beforeState(),
            entry.afterState(),
            entry.createdAt()
        );
    }
}
