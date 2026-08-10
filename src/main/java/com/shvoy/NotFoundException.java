package com.shvoy;

public class NotFoundException extends ApiException {

    public NotFoundException(String message) {
        this(ErrorCode.NOT_FOUND, message);
    }

    public NotFoundException(ErrorCode code, String message) {
        super(code, message);
    }
}
