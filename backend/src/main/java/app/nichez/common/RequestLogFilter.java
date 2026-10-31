package app.nichez.common;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.time.Clock;
import java.time.Instant;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;
import org.springframework.web.servlet.HandlerMapping;

/**
 * Times every call under {@code /api} and hands the result to {@link RequestLogWriter}. It sits outside
 * Spring Security (right after the request id is set), so a 401 or 403 is logged like any other answer.
 *
 * <p>Only these are recorded: when the request arrived, its request id, the method, the route pattern, the
 * status, how long it took, who called and, for a failure, the error code of the body. Never the query string, the body or any header, so a token, a
 * phone number or a search word cannot end up in the log. The real path is kept only when no route matched,
 * which is when the pattern cannot say what was asked for.
 *
 * <p>Each failure is also written to the backend's own log as one line (5xx as WARN, 4xx as INFO), so the
 * host's log viewer shows what went wrong without opening the database.
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 10)
@ConditionalOnProperty(prefix = "request-log", name = "enabled", havingValue = "true")
public class RequestLogFilter extends OncePerRequestFilter {

    /** Set by a filter inside the security chain once the token is verified; read here after the call. */
    public static final String USER_ATTRIBUTE = RequestLogFilter.class.getName() + ".USER_ID";

    /** Set by {@link ProblemFactory} whenever an error body is built: that body's {@code code}. */
    public static final String ERROR_CODE_ATTRIBUTE = RequestLogFilter.class.getName() + ".ERROR_CODE";

    static final String UNMATCHED = "(unmatched)";
    private static final int MAX_PATH = 200;
    private static final int MAX_METHOD = 16;
    private static final int MAX_CODE = 64;

    private static final Logger log = LoggerFactory.getLogger(RequestLogFilter.class);

    private final RequestLogWriter writer;
    private final Clock clock;

    RequestLogFilter(RequestLogWriter writer, Clock clock) {
        this.writer = writer;
        this.clock = clock;
    }

    /** Health checks, Swagger and anything else outside the API are not worth a row. */
    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        return !request.getRequestURI().startsWith("/api/");
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        Instant arrived = clock.instant();
        long started = System.nanoTime();
        int failed = 0;
        try {
            chain.doFilter(request, response);
        } catch (IOException | ServletException | RuntimeException e) {
            failed = HttpServletResponse.SC_INTERNAL_SERVER_ERROR;
            throw e;
        } finally {
            record(request, response, arrived, started, failed);
        }
    }

    private void record(HttpServletRequest request, HttpServletResponse response, Instant arrived, long started,
            int failed) {
        // Whatever goes wrong here must never reach the caller.
        try {
            Object pattern = request.getAttribute(HandlerMapping.BEST_MATCHING_PATTERN_ATTRIBUTE);
            boolean matched = pattern instanceof String p && !p.isBlank() && !"/error".equals(p);
            Object user = request.getAttribute(USER_ATTRIBUTE);
            long millis = (System.nanoTime() - started) / 1_000_000;
            int status = failed != 0 ? failed : response.getStatus();
            String errorCode = errorCode(request, status);
            RequestLogWriter.Entry entry = new RequestLogWriter.Entry(
                    arrived,
                    orUnknown(RequestIdFilter.of(request)),
                    clean(request.getMethod(), MAX_METHOD),
                    matched ? (String) pattern : UNMATCHED,
                    matched ? null : clean(request.getRequestURI(), MAX_PATH),
                    status,
                    (int) Math.min(Integer.MAX_VALUE, millis),
                    user instanceof UUID id ? id : null,
                    errorCode);
            writer.offer(entry);
            if (status >= 400) {
                logFailure(entry);
            }
        } catch (RuntimeException e) {
            log.warn("Request log entry skipped, error={}", e.getClass().getSimpleName());
        }
    }

    /** The code of the error body; INTERNAL_ERROR when an exception escaped before one was written. */
    private static String errorCode(HttpServletRequest request, int status) {
        if (status < 400) {
            return null;
        }
        Object code = request.getAttribute(ERROR_CODE_ATTRIBUTE);
        if (code instanceof String c && !c.isBlank()) {
            return clean(c, MAX_CODE);
        }
        return status >= 500 ? ErrorCode.INTERNAL_ERROR : null;
    }

    /** Route and code only, like the row: no query string, body or header, so nothing secret reaches the log. */
    private static void logFailure(RequestLogWriter.Entry e) {
        String where = e.path() != null ? e.path() : e.route();
        if (e.status() >= 500) {
            log.warn("Request failed, status={} code={} method={} route={} durationMs={} requestId={}",
                    e.status(), e.errorCode(), e.method(), where, e.durationMs(), e.requestId());
        } else {
            log.info("Request failed, status={} code={} method={} route={} durationMs={} requestId={}",
                    e.status(), e.errorCode(), e.method(), where, e.durationMs(), e.requestId());
        }
    }

    private static String orUnknown(String requestId) {
        return requestId != null ? requestId : "unknown";
    }

    /** Control characters out (the database refuses some, and they can forge log lines), then cut to length. */
    private static String clean(String value, int max) {
        String cleaned = value == null ? "" : value.replaceAll("\\p{Cntrl}", "?");
        return cleaned.length() > max ? cleaned.substring(0, max) : cleaned;
    }
}
