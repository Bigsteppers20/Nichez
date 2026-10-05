package app.nichez.common;

import app.nichez.common.ProblemFactory.FieldProblem;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.ConstraintViolationException;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.AuthenticationException;
import org.springframework.validation.FieldError;
import org.springframework.validation.ObjectError;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.context.request.ServletWebRequest;
import org.springframework.web.context.request.WebRequest;
import org.springframework.web.method.annotation.HandlerMethodValidationException;
import org.springframework.web.servlet.mvc.method.annotation.ResponseEntityExceptionHandler;

/**
 * The single place that turns a failure into a response. Every body is a
 * ProblemDetail with a stable {@code code} and a {@code requestId}. Validation
 * failures add an {@code errors} array of field and message, never the rejected value.
 *
 * <p>Anything unexpected becomes a 500 with a generic message. The cause is
 * logged with the request id and is never sent to the client.
 */
@RestControllerAdvice
public class GlobalExceptionHandler extends ResponseEntityExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    // --- our own exceptions -------------------------------------------------

    @ExceptionHandler(ApiException.class)
    ResponseEntity<Object> handleApi(ApiException ex, HttpServletRequest request) {
        ProblemDetail problem = ProblemFactory.create(ex.status(), ex.code(), ex.getMessage(), request);
        ex.properties().forEach(problem::setProperty);
        HttpHeaders headers = new HttpHeaders();
        if (ex.retryAfter() != null) {
            headers.set(HttpHeaders.RETRY_AFTER, Long.toString(Math.max(1, ex.retryAfter().toSeconds())));
        }
        return ResponseEntity.status(ex.status()).headers(headers).body(problem);
    }

    // --- security (when thrown from a controller or service) -----------------

    @ExceptionHandler(AccessDeniedException.class)
    ResponseEntity<Object> handleAccessDenied(AccessDeniedException ex, HttpServletRequest request) {
        ProblemDetail problem = ProblemFactory.create(HttpStatus.FORBIDDEN, ErrorCode.ACCESS_DENIED,
                "You do not have permission to do this.", request);
        return ResponseEntity.status(HttpStatus.FORBIDDEN).body(problem);
    }

    @ExceptionHandler(AuthenticationException.class)
    ResponseEntity<Object> handleAuthentication(AuthenticationException ex, HttpServletRequest request) {
        ProblemDetail problem = ProblemFactory.create(HttpStatus.UNAUTHORIZED, ErrorCode.AUTHENTICATION_REQUIRED,
                "Authentication is required.", request);
        return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                .header(HttpHeaders.WWW_AUTHENTICATE, "Bearer")
                .body(problem);
    }

    // --- validation -------------------------------------------------------

    @Override
    protected ResponseEntity<Object> handleMethodArgumentNotValid(MethodArgumentNotValidException ex,
            HttpHeaders headers, HttpStatusCode status, WebRequest request) {
        ProblemDetail problem = ex.getBody();
        problem.setDetail("Request validation failed.");
        problem.setProperty("code", ErrorCode.VALIDATION_FAILED);
        List<FieldProblem> errors = ex.getBindingResult().getAllErrors().stream()
                .map(GlobalExceptionHandler::toFieldProblem)
                .toList();
        ProblemFactory.errors(problem, errors);
        return handleExceptionInternal(ex, problem, headers, status, request);
    }

    @Override
    protected ResponseEntity<Object> handleHandlerMethodValidationException(HandlerMethodValidationException ex,
            HttpHeaders headers, HttpStatusCode status, WebRequest request) {
        ProblemDetail problem = ex.getBody();
        problem.setDetail("Request validation failed.");
        problem.setProperty("code", ErrorCode.VALIDATION_FAILED);
        List<FieldProblem> errors = ex.getParameterValidationResults().stream()
                .flatMap(result -> result.getResolvableErrors().stream()
                        .map(error -> new FieldProblem(
                                result.getMethodParameter().getParameterName(), error.getDefaultMessage())))
                .toList();
        ProblemFactory.errors(problem, errors);
        return handleExceptionInternal(ex, problem, headers, status, request);
    }

    @ExceptionHandler(ConstraintViolationException.class)
    ResponseEntity<Object> handleConstraintViolation(ConstraintViolationException ex, HttpServletRequest request) {
        ProblemDetail problem = ProblemFactory.create(HttpStatus.BAD_REQUEST, ErrorCode.VALIDATION_FAILED,
                "Request validation failed.", request);
        ProblemFactory.errors(problem, ex.getConstraintViolations().stream()
                .map(v -> new FieldProblem(lastNode(v.getPropertyPath().toString()), v.getMessage()))
                .toList());
        return ResponseEntity.badRequest().body(problem);
    }

    @Override
    protected ResponseEntity<Object> handleHttpMessageNotReadable(HttpMessageNotReadableException ex,
            HttpHeaders headers, HttpStatusCode status, WebRequest request) {
        // Never echo the parser message: it can quote the offending input.
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(status, "The request body is missing or malformed.");
        problem.setProperty("code", ErrorCode.BAD_REQUEST);
        return handleExceptionInternal(ex, problem, headers, status, request);
    }

    // --- everything else ------------------------------------------------------

    @ExceptionHandler(Exception.class)
    ResponseEntity<Object> handleUnexpected(Exception ex, HttpServletRequest request) {
        log.error("Unhandled exception, requestId={}", RequestIdFilter.of(request), ex);
        ProblemDetail problem = ProblemFactory.create(HttpStatus.INTERNAL_SERVER_ERROR, ErrorCode.INTERNAL_ERROR,
                "An unexpected error occurred.", request);
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(problem);
    }

    /** Decorates the framework's own ProblemDetails (404, 405, 415, ...) with code and requestId. */
    @Override
    protected ResponseEntity<Object> handleExceptionInternal(Exception ex, Object body, HttpHeaders headers,
            HttpStatusCode statusCode, WebRequest webRequest) {
        HttpServletRequest request = webRequest instanceof ServletWebRequest servlet ? servlet.getRequest() : null;
        Object decorated = body;
        if (body instanceof ProblemDetail problem) {
            ProblemFactory.decorate(problem, ProblemFactory.codeFor(statusCode), request);
        } else if (body == null) {
            decorated = ProblemFactory.create(statusCode, ProblemFactory.codeFor(statusCode),
                    HttpStatus.valueOf(statusCode.value()).getReasonPhrase(), request);
        }
        return super.handleExceptionInternal(ex, decorated, headers, statusCode, webRequest);
    }

    private static FieldProblem toFieldProblem(ObjectError error) {
        String field = error instanceof FieldError fieldError ? fieldError.getField() : error.getObjectName();
        return new FieldProblem(field, error.getDefaultMessage());
    }

    private static String lastNode(String path) {
        int dot = path.lastIndexOf('.');
        return dot >= 0 ? path.substring(dot + 1) : path;
    }
}
