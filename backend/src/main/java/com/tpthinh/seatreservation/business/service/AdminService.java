package com.tpthinh.seatreservation.business.service;

import com.tpthinh.seatreservation.business.domain.model.AuditEntry;
import com.tpthinh.seatreservation.business.domain.model.Booking;
import com.tpthinh.seatreservation.business.domain.model.Seat;
import com.tpthinh.seatreservation.business.port.in.GetAuditLogsUseCase;
import com.tpthinh.seatreservation.business.port.in.GetPendingBookingsUseCase;
import com.tpthinh.seatreservation.business.port.external.AuditPort;
import com.tpthinh.seatreservation.business.port.external.BookingRepositoryPort;
import com.tpthinh.seatreservation.business.port.external.SeatRepositoryPort;
import org.springframework.stereotype.Service;
import java.util.ArrayList;
import java.util.List;

@Service
public class AdminService implements GetPendingBookingsUseCase, GetAuditLogsUseCase {
    private final BookingRepositoryPort bookingRepo;
    private final SeatRepositoryPort seatRepo;
    private final AuditPort auditPort;

    public AdminService(BookingRepositoryPort bookingRepo,
                        SeatRepositoryPort seatRepo,
                        AuditPort auditPort) {
        this.bookingRepo = bookingRepo;
        this.seatRepo = seatRepo;
        this.auditPort = auditPort;
    }

    @Override
    public List<PendingBookingResult> getPendingBookings() {
        List<Booking> pending = bookingRepo.findAllPending();
        List<PendingBookingResult> results = new ArrayList<>();
        
        for (Booking booking : pending) {
            String seatLabel = seatRepo.findById(booking.seatId()).map(Seat::label).orElse("Unknown");
            results.add(new PendingBookingResult(booking, seatLabel));
        }
        
        return results;
    }

    @Override
    public List<AuditEntry> getAuditLogs(String entityType, String action, int limit) {
        return auditPort.queryLogs(entityType, action, limit);
    }
}
