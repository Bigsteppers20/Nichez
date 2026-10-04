package app.nichez.security;

import app.nichez.common.ErrorCode;
import app.nichez.common.ProblemFactory;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ProblemDetail;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.oauth2.core.OAuth2AuthenticationException;
import org.springframework.security.web.AuthenticationEntryPoint;
import org.springframework.security.web.access.AccessDeniedHandler;

/**
 * 401 and 403 raised inside the security filter chain never reach the
 * controller advice, so they are written here in the same ProblemDetail shape.
 * Messages are fixed text: nothing from the token or the exception is echoed.
 */
final class ProblemSecurityHandlers {

    private ProblemSecurityHandlers() {
    }

    static AuthenticationEntryPoint entryPoint(ObjectMapper mapper) {
        return (request, response, ex) -> {
            String code;
            String detail;
            String challenge;
            if (ex instanceof OAuth2AuthenticationException) {
                boolean expired = mentionsExpiry(ex);
                code = expired ? ErrorCode.TOKEN_EXPIRED : ErrorCode.TOKEN_INVALID;
                detail = expired ? "The access token has expired." : "The access token is not valid.";
                challenge = "Bearer error=\"invalid_token\"";
            } else {
                code = ErrorCode.AUTHENTICATION_REQUIRED;
                detail = "Authentication is required.";
                challenge = "Bearer";
            }
            response.setHeader(HttpHeaders.WWW_AUTHENTICATE, challenge);
            write(mapper, request, response, HttpStatus.UNAUTHORIZED, code, detail);
        };
    }

    static AccessDeniedHandler accessDeniedHandler(ObjectMapper mapper) {
        return (request, response, ex) -> write(mapper, request, response, HttpStatus.FORBIDDEN,
                ErrorCode.ACCESS_DENIED, "You do not have permission to do this.");
    }

    /** Spring's timestamp validator reports "Jwt expired at ..."; match that, not the whole message. */
    private static boolean mentionsExpiry(AuthenticationException ex) {
        for (Throwable t = ex; t != null; t = t.getCause()) {
            String message = t.getMessage();
            if (message != null && message.contains("Jwt expired")) {
                return true;
            }
            if (t.getCause() == t) {
                break;
            }
        }
        return false;
    }

    private static void write(ObjectMapper mapper, HttpServletRequest request, HttpServletResponse response,
            HttpStatus status, String code, String detail) throws IOException {
        ProblemDetail problem = ProblemFactory.create(status, code, detail, request);
        response.setStatus(status.value());
        response.setContentType(MediaType.APPLICATION_PROBLEM_JSON_VALUE);
        response.setCharacterEncoding("UTF-8");
        mapper.writeValue(response.getOutputStream(), problem);
    }
}
