package com.tpthinh.seatreservation.business.port.in;

import com.tpthinh.seatreservation.business.domain.model.AuditEntry;
import java.util.List;

public interface GetAuditLogsUseCase {
    List<AuditEntry> getAuditLogs(String entityType, String action, int limit);
}
