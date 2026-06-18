package com.tpthinh.seatreservation.business.port.external;

import com.tpthinh.seatreservation.business.domain.model.AuditEntry;
import java.util.List;

public interface AuditPort {
    void log(String actor, String action, String entityType, String entityId, Object beforeState, Object afterState);
    List<AuditEntry> queryLogs(String entityType, String action, int limit);
}
