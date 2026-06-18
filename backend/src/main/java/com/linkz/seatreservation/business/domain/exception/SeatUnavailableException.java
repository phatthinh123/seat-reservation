package com.tpthinh.seatreservation.business.domain.exception;

public class SeatUnavailableException extends RuntimeException {
    public SeatUnavailableException(String message) {
        super(message);
    }
}
