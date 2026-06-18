package com.tpthinh.seatreservation.business.port.in;

import java.util.UUID;

public interface ReconcilePaymentUseCase {
    void reconcile(UUID bookingId);
    void reconcilePendingBookings();
    void releaseExpiredHolds();
}
