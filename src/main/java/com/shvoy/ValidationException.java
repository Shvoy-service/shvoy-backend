package com.shvoy;

/**
 * For validation failures raised by hand in service code — a cross-field
 * check Bean Validation can't express cleanly (e.g. validTo not before
 * validFrom), or a batch of per-row errors collapsed into one message (see
 * ApiExceptionHandler.handleValidation, which does the same for
 * MethodArgumentNotValidException) — as opposed to the framework-driven
 * VALIDATION_ERROR cases (@Valid, malformed JSON), which never needed a
 * throwable of their own until now.
 */
public class ValidationException extends ApiException {

    public ValidationException(String message) {
        super(ErrorCode.VALIDATION_ERROR, message);
    }
}
