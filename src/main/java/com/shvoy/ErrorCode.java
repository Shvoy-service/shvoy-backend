package com.shvoy;

import org.springframework.http.HttpStatus;

/**
 * The full catalogue of machine-readable error codes the API can return —
 * see ApiExceptionHandler for where every one of these gets turned into a
 * response. This is a published contract: the frontend maps its own UI copy
 * from {@code code}, not from {@code message} (which is informational only
 * and can change wording freely). Once a code exists here, its meaning is
 * fixed — add a new one rather than repurposing an existing one, even if the
 * new case is similar.
 */
public enum ErrorCode {

    DUPLICATE_EMAIL(HttpStatus.CONFLICT),
    DUPLICATE_SUPPLIER(HttpStatus.CONFLICT),
    DUPLICATE_SKU(HttpStatus.CONFLICT),
    AMBIGUOUS_PRICE_WINDOW(HttpStatus.CONFLICT),
    CURRENCY_MISMATCH(HttpStatus.CONFLICT),
    PO_NOT_EDITABLE(HttpStatus.CONFLICT),
    PO_HAS_EXPIRED_PRICES(HttpStatus.CONFLICT),
    PO_NOT_READY_TO_GENERATE(HttpStatus.CONFLICT),
    LAST_ACTIVE_ADMIN(HttpStatus.CONFLICT),
    INVALID_INVITE(HttpStatus.NOT_FOUND),
    NOT_FOUND(HttpStatus.NOT_FOUND),
    VALIDATION_ERROR(HttpStatus.BAD_REQUEST),
    FORBIDDEN(HttpStatus.FORBIDDEN),
    UNAUTHENTICATED(HttpStatus.UNAUTHORIZED);

    private final HttpStatus status;

    ErrorCode(HttpStatus status) {
        this.status = status;
    }

    public HttpStatus status() {
        return status;
    }
}
