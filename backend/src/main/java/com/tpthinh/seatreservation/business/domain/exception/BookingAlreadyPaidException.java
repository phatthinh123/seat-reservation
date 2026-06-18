package com.tpthinh.seatreservation.business.domain.exception;

public class BookingAlreadyPaidException extends RuntimeException {
    public BookingAlreadyPaidException(String message) {
        super(message);
    }
}
