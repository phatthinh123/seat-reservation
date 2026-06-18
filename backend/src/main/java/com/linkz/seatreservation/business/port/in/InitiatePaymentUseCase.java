package com.linkz.seatreservation.business.port.in;

import com.linkz.seatreservation.business.domain.model.Payment;
import java.util.UUID;

public interface InitiatePaymentUseCase {
    Payment initiatePayment(InitiatePaymentCommand command);

    record InitiatePaymentCommand(UUID bookingId, String userId, boolean simulateFail) {}
}
