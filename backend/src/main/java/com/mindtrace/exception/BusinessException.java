package com.mindtrace.exception;

import lombok.Getter;

@Getter
public class BusinessException extends RuntimeException {
    private final int status;

    public BusinessException(String message) {
        this(400, message);
    }

    public BusinessException(int status, String message) {
        super(message);
        this.status = status;
    }
}

