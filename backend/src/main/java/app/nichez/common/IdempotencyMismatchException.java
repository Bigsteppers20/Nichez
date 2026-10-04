package app.nichez.common;

import org.springframework.http.HttpStatus;

/** 422: an Idempotency-Key was reused with a different request body. */
public class IdempotencyMismatchException extends ApiException {

    public IdempotencyMismatchException() {
        super(HttpStatus.UNPROCESSABLE_ENTITY, ErrorCode.IDEMPOTENCY_KEY_MISMATCH,
                "This Idempotency-Key was already used with a different request.");
    }
}
