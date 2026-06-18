package com.tpthinh.mockpayment;

public record PaymentRecord(String bookingId, PaymentStatus status, boolean simulateFail) {
    public PaymentRecord withStatus(PaymentStatus newStatus) {
        return new PaymentRecord(bookingId, newStatus, simulateFail);
    }
}
