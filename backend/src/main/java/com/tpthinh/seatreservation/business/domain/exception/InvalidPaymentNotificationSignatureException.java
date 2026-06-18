package com.tpthinh.seatreservation.business.domain.exception;

public class InvalidPaymentNotificationSignatureException extends RuntimeException {
    public InvalidPaymentNotificationSignatureException(String message) {
        super(message);
    }
}
