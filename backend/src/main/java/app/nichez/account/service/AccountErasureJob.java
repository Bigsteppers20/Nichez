package app.nichez.account.service;

import app.nichez.account.repository.AccountRepository;
import app.nichez.common.AuditService;
import app.nichez.config.AccountProperties;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Daily: erases the accounts whose deletion was requested more than {@code account.deletion-grace}
 * ago (see {@link AccountRepository#erase}). Each person is erased in its own transaction, together
 * with its audit row, so one failure leaves the others alone and is retried the next day.
 *
 * <p>This clears everything in the application's own tables. Removing the sign-in record itself
 * ({@code auth.users}) needs Supabase's admin API, which this service deliberately has no key for;
 * until that exists, an erased person who signs in again is refused by the account-state filter.
 */
@Component
public class AccountErasureJob {

    public static final String ACCOUNT_ERASED_EVENT = "ACCOUNT_ERASED";

    private static final Logger log = LoggerFactory.getLogger(AccountErasureJob.class);

    private final AccountRepository accounts;
    private final AccountProperties properties;
    private final AuditService audit;
    private final TransactionTemplate transaction;
    private final Clock clock;

    AccountErasureJob(AccountRepository accounts, AccountProperties properties, AuditService audit,
            TransactionTemplate transaction, Clock clock) {
        this.accounts = accounts;
        this.properties = properties;
        this.audit = audit;
        this.transaction = transaction;
        this.clock = clock;
    }

    @Scheduled(cron = "${account.deletion-cron}", zone = "UTC")
    void scheduled() {
        run();
    }

    /** One pass over everyone who is due. Returns how many were erased. */
    public int run() {
        Instant now = clock.instant();
        Instant cutoff = now.minus(properties.deletionGrace());
        int erased = 0;
        int failed = 0;
        List<UUID> due = accounts.dueForErasure(cutoff, properties.deletionBatchSize());
        for (UUID userId : due) {
            try {
                transaction.executeWithoutResult(status -> {
                    accounts.erase(userId, now);
                    audit.record(ACCOUNT_ERASED_EVENT, null, Map.of("userId", userId.toString()));
                });
                erased++;
            } catch (RuntimeException e) {
                failed++;
                // Class name only: the message can carry row values.
                log.warn("Account erasure failed, user={}, error={}", userId, e.getClass().getSimpleName());
            }
        }
        if (erased > 0 || failed > 0) {
            log.info("Account erasure pass done, erased={}, failed={}", erased, failed);
        }
        return erased;
    }
}
