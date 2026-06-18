package com.tpthinh.seatreservation.web.dto;

import com.tpthinh.seatreservation.business.domain.model.Payment;
import java.util.UUID;

public record PaymentResponse(
    String paymentId,
    UUID bookingId,
    String status
) {
    public static PaymentResponse from(Payment payment) {
        if (payment == null) return null;
        return new PaymentResponse(
            payment.externalPaymentId(),
            payment.bookingId(),
            payment.status().name()
        );
    }
}
