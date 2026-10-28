package app.nichez.common;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

import app.nichez.config.RequestLogProperties;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.jdbc.core.JdbcTemplate;

/** The writer without a database: a full queue never blocks and a database outage never reaches a caller. */
class RequestLogWriterTests {

    private final JdbcTemplate jdbc = mock(JdbcTemplate.class);

    private RequestLogWriter writer(int batchSize, int capacity) {
        return new RequestLogWriter(jdbc,
                new RequestLogProperties(true, Duration.ofSeconds(5), batchSize, capacity, Duration.ofDays(30),
                        "0 15 3 * * *"),
                Clock.systemUTC());
    }

    private static RequestLogWriter.Entry entry(int n) {
        return new RequestLogWriter.Entry(Instant.parse("2026-10-27T10:00:00Z"), "req-" + n, "GET", "/api/v1/ping",
                null, 200, n, null);
    }

    @Test
    void aFullQueueDropsTheNewestEntryInsteadOfWaiting() {
        RequestLogWriter writer = writer(10, 2);

        assertThat(writer.offer(entry(1))).isTrue();
        assertThat(writer.offer(entry(2))).isTrue();
        assertThat(writer.offer(entry(3))).isFalse();
    }

    @Test
    void entriesAreWrittenInBatchesOfTheConfiguredSize() {
        doReturn(new int[0][0]).when(jdbc).batchUpdate(anyString(), anyCollection(), anyInt(), any());
        RequestLogWriter writer = writer(2, 10);
        for (int i = 1; i <= 5; i++) {
            writer.offer(entry(i));
        }

        assertThat(writer.flush()).isEqualTo(5);

        verify(jdbc, times(2)).batchUpdate(anyString(), anyCollection(), eq(2), any());
        verify(jdbc, times(1)).batchUpdate(anyString(), anyCollection(), eq(1), any());
    }

    @Test
    void aFailedInsertDoesNotThrowAndIsRetriedAtTheNextFlush() {
        doThrow(new DataAccessResourceFailureException("db down"))
                .doReturn(new int[0][0])
                .when(jdbc).batchUpdate(anyString(), anyCollection(), anyInt(), any());
        RequestLogWriter writer = writer(10, 10);
        writer.offer(entry(1));
        writer.offer(entry(2));

        assertThatCode(writer::flush).doesNotThrowAnyException();
        assertThat(writer.flush()).as("the same two entries, written once the database is back").isEqualTo(2);

        verify(jdbc, times(2)).batchUpdate(anyString(), anyCollection(), eq(2), any());
        assertThat(writer.flush()).as("nothing is written twice").isZero();
    }

}
