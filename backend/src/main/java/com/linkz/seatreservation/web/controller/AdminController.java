package com.linkz.seatreservation.web.controller;

import com.linkz.seatreservation.business.port.in.GetAuditLogsUseCase;
import com.linkz.seatreservation.business.port.in.GetPendingBookingsUseCase;
import com.linkz.seatreservation.business.port.in.ReconcilePaymentUseCase;
import com.linkz.seatreservation.web.dto.AdminBookingDto;
import com.linkz.seatreservation.web.dto.AuditLogDto;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import java.util.List;
import java.util.UUID;

@RestController
public class AdminController {
    private final GetPendingBookingsUseCase getPendingBookingsUseCase;
    private final ReconcilePaymentUseCase reconcileUseCase;
    private final GetAuditLogsUseCase getAuditLogsUseCase;

    public AdminController(GetPendingBookingsUseCase getPendingBookingsUseCase,
                           ReconcilePaymentUseCase reconcileUseCase,
                           GetAuditLogsUseCase getAuditLogsUseCase) {
        this.getPendingBookingsUseCase = getPendingBookingsUseCase;
        this.reconcileUseCase = reconcileUseCase;
        this.getAuditLogsUseCase = getAuditLogsUseCase;
    }

    @GetMapping("/api/admin/pending-bookings")
    public ResponseEntity<List<AdminBookingDto>> getPendingBookings() {
        var results = getPendingBookingsUseCase.getPendingBookings();
        var dtos = results.stream()
            .map(r -> AdminBookingDto.from(r.booking(), r.seatLabel()))
            .toList();
        return ResponseEntity.ok(dtos);
    }

    @PostMapping("/api/admin/reconcile/{bookingId}")
    public ResponseEntity<Void> reconcileBooking(@PathVariable("bookingId") UUID bookingId) {
        reconcileUseCase.reconcile(bookingId);
        return ResponseEntity.ok().build();
    }

    @GetMapping("/api/admin/audit-logs")
    public ResponseEntity<List<AuditLogDto>> getAuditLogs(
            @RequestParam(value = "entityType", required = false) String entityType,
            @RequestParam(value = "action", required = false) String action,
            @RequestParam(value = "limit", defaultValue = "50") int limit) {
        
        var logs = getAuditLogsUseCase.getAuditLogs(entityType, action, limit);
        var dtos = logs.stream().map(AuditLogDto::from).toList();
        return ResponseEntity.ok(dtos);
    }
}
