package app.nichez.common;

import jakarta.servlet.http.HttpServletRequest;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.ProblemDetail;

/** Builds the one error body every failure uses: RFC 9457 ProblemDetail plus code, requestId, errors. */
public final class ProblemFactory {

    public record FieldProblem(String field, String message) {
    }

    private ProblemFactory() {
    }

    public static ProblemDetail create(HttpStatusCode status, String code, String detail,
            HttpServletRequest request) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(status, detail);
        decorate(problem, code, request);
        return problem;
    }

    /** Adds title, code and requestId. A code that is already set is kept. */
    public static void decorate(ProblemDetail problem, String code, HttpServletRequest request) {
        HttpStatus resolved = HttpStatus.resolve(problem.getStatus());
        if (problem.getTitle() == null && resolved != null) {
            problem.setTitle(resolved.getReasonPhrase());
        }
        if (problem.getProperties() == null || !problem.getProperties().containsKey("code")) {
            problem.setProperty("code", code);
        }
        String requestId = request != null ? RequestIdFilter.of(request) : null;
        if (requestId != null) {
            problem.setProperty("requestId", requestId);
        }
    }

    public static void errors(ProblemDetail problem, List<FieldProblem> errors) {
        problem.setProperty("errors", errors);
    }

    /** Fallback code for framework errors, chosen from the status. */
    public static String codeFor(HttpStatusCode status) {
        return switch (status.value()) {
            case 400 -> ErrorCode.BAD_REQUEST;
            case 401 -> ErrorCode.AUTHENTICATION_REQUIRED;
            case 403 -> ErrorCode.ACCESS_DENIED;
            case 404 -> ErrorCode.NOT_FOUND;
            case 405 -> ErrorCode.METHOD_NOT_ALLOWED;
            case 406 -> ErrorCode.NOT_ACCEPTABLE;
            case 409 -> ErrorCode.CONFLICT;
            case 413 -> ErrorCode.PAYLOAD_TOO_LARGE;
            case 415 -> ErrorCode.UNSUPPORTED_MEDIA_TYPE;
            case 422 -> ErrorCode.VALIDATION_FAILED;
            case 429 -> ErrorCode.RATE_LIMITED;
            default -> ErrorCode.INTERNAL_ERROR;
        };
    }
}
