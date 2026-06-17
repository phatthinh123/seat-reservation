package com.linkz.seatreservation.business.port.in;

import com.linkz.seatreservation.business.domain.model.AuditEntry;
import java.util.List;

public interface GetAuditLogsUseCase {
    List<AuditEntry> getAuditLogs(String entityType, String action, int limit);
}
