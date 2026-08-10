package com.shvoy;

import java.time.Instant;

/**
 * The one error body shape returned by every failure path in the API — see
 * ErrorCode for the catalogue. {@code message} is informational/debugging
 * only; the frontend maps its own copy from {@code code}, which is the
 * stable part of this contract.
 */
record ErrorResponse(String code, int status, String message, Instant timestamp, String path) {

    static ErrorResponse of(ErrorCode code, String message, String path) {
        return new ErrorResponse(code.name(), code.status().value(), message, Instant.now(), path);
    }
}
