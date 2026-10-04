package app.nichez.common;

import org.springframework.http.HttpStatus;

/** 409: the request is valid but conflicts with the current state of the resource. */
public class ConflictException extends ApiException {

    public ConflictException(String code, String message) {
        super(HttpStatus.CONFLICT, code, message);
    }
}
