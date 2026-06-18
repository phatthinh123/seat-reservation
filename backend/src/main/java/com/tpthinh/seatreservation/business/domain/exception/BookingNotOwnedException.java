package com.tpthinh.seatreservation.business.domain.exception;

public class BookingNotOwnedException extends RuntimeException {
    public BookingNotOwnedException(String message) {
        super(message);
    }
}
