package com.shvoy;

/**
 * Base for every exception ApiExceptionHandler formats via the standard
 * error body — see ErrorCode. Not meant to be thrown directly; throw one of
 * its subclasses (or a new one, for a genuinely new failure shape) so the
 * code is fixed at the throw site rather than inferred from a message
 * string in the handler.
 */
abstract class ApiException extends RuntimeException {

    private final ErrorCode code;

    ApiException(ErrorCode code, String message) {
        super(message);
        this.code = code;
    }

    ErrorCode code() {
        return code;
    }
}
