package app.nichez.config;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/**
 * Limits for the social features. Allowances are per signed-in user, counted in memory per
 * instance (see {@code SlidingWindowRateLimiter}).
 * Env: SOCIAL_VIEW_MIN_WATCHED_MS, SOCIAL_VIEW_BATCH_MAX, SOCIAL_VIEW_BATCHES_PER_MINUTE, SOCIAL_STORY_TTL, SOCIAL_REACTIONS_PER_MINUTE,
 * SOCIAL_COMMENTS_PER_HOUR, SOCIAL_FOLLOWS_PER_HOUR, SOCIAL_REPORTS_PER_HOUR, SOCIAL_SEARCHES_PER_MINUTE, SOCIAL_PUBLIC_LOOKUPS_PER_MINUTE,
 * SOCIAL_STORIES_PER_DAY, SOCIAL_COMMUNITIES_PER_DAY, SOCIAL_NICHES_PER_DAY.
 *
 * <p>{@code viewMinWatchedMs} is how long a playback must last to count as a view. The business
 * value is still to be agreed (see the earnings design), so 3 seconds is a placeholder.
 */
@Validated
@ConfigurationProperties("social")
public record SocialProperties(
        @Positive int viewMinWatchedMs,
        @Positive int viewBatchMax,
        @Positive int viewBatchesPerMinute,
        @NotNull Duration storyTtl,
        @Positive int reactionsPerMinute,
        @Positive int commentsPerHour,
        @Positive int followsPerHour,
        @Positive int reportsPerHour,
        @Positive int searchesPerMinute,
        @Positive int publicLookupsPerMinute,
        @Positive int storiesPerDay,
        @Positive int communitiesPerDay,
        @Positive int nichesPerDay) {
}
