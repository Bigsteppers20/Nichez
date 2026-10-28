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
 * status, how long it took and who called. Never the query string, the body or any header, so a token, a
 * phone number or a search word cannot end up in the log. The real path is kept only when no route matched,
 * which is when the pattern cannot say what was asked for.
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 10)
@ConditionalOnProperty(prefix = "request-log", name = "enabled", havingValue = "true")
public class RequestLogFilter extends OncePerRequestFilter {

    /** Set by a filter inside the security chain once the token is verified; read here after the call. */
    public static final String USER_ATTRIBUTE = RequestLogFilter.class.getName() + ".USER_ID";

    static final String UNMATCHED = "(unmatched)";
    private static final int MAX_PATH = 200;
    private static final int MAX_METHOD = 16;

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
            writer.offer(new RequestLogWriter.Entry(
                    arrived,
                    orUnknown(RequestIdFilter.of(request)),
                    clean(request.getMethod(), MAX_METHOD),
                    matched ? (String) pattern : UNMATCHED,
                    matched ? null : clean(request.getRequestURI(), MAX_PATH),
                    failed != 0 ? failed : response.getStatus(),
                    (int) Math.min(Integer.MAX_VALUE, millis),
                    user instanceof UUID id ? id : null));
        } catch (RuntimeException e) {
            log.warn("Request log entry skipped, error={}", e.getClass().getSimpleName());
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
