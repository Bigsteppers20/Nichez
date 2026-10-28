package app.nichez.common;

import app.nichez.config.RequestLogProperties;
import jakarta.annotation.PreDestroy;
import java.sql.Timestamp;
import java.sql.Types;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.atomic.AtomicLong;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Keeps the API request log in the {@code request_logs} table. A request only puts a small entry on an
 * in-memory queue; this class writes the queue to the database every {@code request-log.flush-interval}.
 * That keeps a slow or unavailable database out of the request path:
 * <ul>
 *   <li>the queue is bounded; when it is full the newest entries are dropped and counted, never waited for;</li>
 *   <li>a failed insert is retried at the next flush, and {@link #flush()} never throws;</li>
 *   <li>what is logged on a failure is the exception class and a count, never row values.</li>
 * </ul>
 */
@Component
@ConditionalOnProperty(prefix = "request-log", name = "enabled", havingValue = "true")
public class RequestLogWriter {

    /** One finished request. {@code path} is set only when no route matched; {@code userId} only when signed in. */
    public record Entry(Instant at, String requestId, String method, String route, String path, int status,
            int durationMs, UUID userId) {
    }

    private static final Logger log = LoggerFactory.getLogger(RequestLogWriter.class);

    private static final String INSERT = """
            insert into request_logs (created_at, request_id, method, route, path, status, duration_ms, user_id)
            values (?, ?, ?, ?, ?, ?, ?, ?)""";
    private static final int PURGE_CHUNK = 5000;

    private final JdbcTemplate jdbc;
    private final RequestLogProperties properties;
    private final Clock clock;
    private final BlockingQueue<Entry> queue;
    private final AtomicLong dropped = new AtomicLong();
    /** A batch whose insert failed, tried again first at the next flush. Only touched inside {@link #flush()}. */
    private List<Entry> retry = List.of();

    RequestLogWriter(JdbcTemplate jdbc, RequestLogProperties properties, Clock clock) {
        this.jdbc = jdbc;
        this.properties = properties;
        this.clock = clock;
        this.queue = new ArrayBlockingQueue<>(properties.queueCapacity());
    }

    /** Never blocks. Returns false when the queue is full and the entry was dropped. */
    public boolean offer(Entry entry) {
        boolean queued = queue.offer(entry);
        if (!queued) {
            dropped.incrementAndGet();
        }
        return queued;
    }

    @Scheduled(fixedDelayString = "${request-log.flush-interval}")
    void scheduledFlush() {
        flush();
    }

    @PreDestroy
    void flushOnShutdown() {
        flush();
    }

    /** Writes everything waiting, one batch at a time. Returns the rows written. Never throws. */
    public synchronized int flush() {
        int written = 0;
        try {
            if (!retry.isEmpty()) {
                insert(retry);
                written += retry.size();
                retry = List.of();
            }
            List<Entry> batch = new ArrayList<>(properties.batchSize());
            while (queue.drainTo(batch, properties.batchSize()) > 0) {
                try {
                    insert(batch);
                    written += batch.size();
                    batch = new ArrayList<>(properties.batchSize());
                } catch (RuntimeException e) {
                    retry = batch;
                    throw e;
                }
            }
        } catch (RuntimeException e) {
            // Class name only: the message can carry row values.
            log.warn("Request log write failed, will retry, waiting={}, error={}", queue.size() + retry.size(),
                    e.getClass().getSimpleName());
        }
        long lost = dropped.getAndSet(0);
        if (lost > 0) {
            log.warn("Request log queue was full, dropped={}", lost);
        }
        return written;
    }

    private void insert(List<Entry> batch) {
        jdbc.batchUpdate(INSERT, batch, batch.size(), (ps, e) -> {
            ps.setTimestamp(1, Timestamp.from(e.at()));
            ps.setString(2, e.requestId());
            ps.setString(3, e.method());
            ps.setString(4, e.route());
            ps.setString(5, e.path());
            ps.setInt(6, e.status());
            ps.setInt(7, e.durationMs());
            if (e.userId() == null) {
                ps.setNull(8, Types.OTHER);
            } else {
                ps.setObject(8, e.userId());
            }
        });
    }

    @Scheduled(cron = "${request-log.purge-cron}", zone = "UTC")
    void scheduledPurge() {
        try {
            purge();
        } catch (RuntimeException e) {
            log.warn("Request log purge failed, error={}", e.getClass().getSimpleName());
        }
    }

    /** Deletes rows older than {@code request-log.retention}, a chunk at a time. Returns how many went. */
    public int purge() {
        Timestamp cutoff = Timestamp.from(clock.instant().minus(properties.retention()));
        int total = 0;
        int deleted;
        do {
            deleted = jdbc.update("""
                    delete from request_logs where id in (
                        select id from request_logs where created_at < ? order by created_at limit ?)""",
                    cutoff, PURGE_CHUNK);
            total += deleted;
        } while (deleted == PURGE_CHUNK);
        if (total > 0) {
            log.info("Request log purge done, deleted={}", total);
        }
        return total;
    }
}
