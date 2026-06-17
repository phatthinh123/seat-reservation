package com.linkz.seatreservation.business.port.external;

import java.math.BigDecimal;
import java.util.UUID;

public interface PaymentGatewayPort {
    String initiatePayment(UUID bookingId, BigDecimal amount, String callbackUrl, boolean simulateFail);
    void refund(String externalPaymentId);
    String queryStatus(String externalPaymentId);
}
