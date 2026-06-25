package com.tickefy.checkin.modules.vip.exception;

public class CsvUnavailableException extends RuntimeException {

    public CsvUnavailableException(String message) {
        super(message);
    }

    public CsvUnavailableException(String message, Throwable cause) {
        super(message, cause);
    }
}
