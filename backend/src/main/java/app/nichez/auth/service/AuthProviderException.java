package app.nichez.auth.service;

import app.nichez.common.ApiException;
import org.springframework.http.HttpStatus;

/** Supabase Auth could not be reached or answered with something we cannot use. 502, safe to retry. */
public class AuthProviderException extends ApiException {

    public static final String CODE = "AUTH_PROVIDER_UNAVAILABLE";

    public AuthProviderException() {
        super(HttpStatus.BAD_GATEWAY, CODE, "We could not reach the sign-in service. Try again in a moment.");
    }
}
