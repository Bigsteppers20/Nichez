package app.nichez.common;

import org.springframework.http.HttpStatus;

/** 404. Also use it when the caller may not know the resource exists (blocked, private). */
public class NotFoundException extends ApiException {

    public NotFoundException(String message) {
        this(ErrorCode.NOT_FOUND, message);
    }

    public NotFoundException(String code, String message) {
        super(HttpStatus.NOT_FOUND, code, message);
    }
}
