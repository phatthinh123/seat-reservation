package com.tpthinh.seatreservation.business.port.in;

import com.tpthinh.seatreservation.business.domain.model.Payment;
import java.util.UUID;

public interface InitiatePaymentUseCase {
    Payment initiatePayment(InitiatePaymentCommand command);

    record InitiatePaymentCommand(UUID bookingId, String userId, boolean simulateFail, boolean simulateDelay) {}
}
