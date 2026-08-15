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
    PO_NOT_SENDABLE(HttpStatus.CONFLICT),
    PO_NOT_READY_FOR_PI(HttpStatus.CONFLICT),
    PO_NOT_READY_FOR_INVOICE(HttpStatus.CONFLICT),
    PO_NOT_READY_FOR_SHIPMENT(HttpStatus.CONFLICT),
    PO_ALREADY_CONSIGNED(HttpStatus.CONFLICT),
    CONSIGNMENT_NOT_DETACHABLE(HttpStatus.CONFLICT),
    CONSIGNMENT_NOT_RECEIPT_ELIGIBLE(HttpStatus.CONFLICT),
    PROVISIONAL_GRN_EXISTS(HttpStatus.CONFLICT),
    PROVISIONAL_GRN_NOT_AMENDABLE(HttpStatus.CONFLICT),
    CREDIT_NOT_OPEN(HttpStatus.CONFLICT),
    DISCREPANCY_NOT_OPEN(HttpStatus.CONFLICT),
    SUPPLIER_MISSING_CONTACT_EMAIL(HttpStatus.CONFLICT),
    LAST_ACTIVE_ADMIN(HttpStatus.CONFLICT),
    INELIGIBLE_APPROVER(HttpStatus.CONFLICT),
    APPROVER_COUNT_EXCEEDS_POOL(HttpStatus.CONFLICT),
    PI_NOT_AWAITING_APPROVAL(HttpStatus.CONFLICT),
    NOT_IN_APPROVER_POOL(HttpStatus.CONFLICT),
    SELF_APPROVAL_FORBIDDEN(HttpStatus.CONFLICT),
    ALREADY_SIGNED_OFF(HttpStatus.CONFLICT),
    INVALID_STATUS_TRANSITION(HttpStatus.CONFLICT),
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
