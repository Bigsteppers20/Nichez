package app.nichez.activity.dto;

import java.time.Instant;
import java.util.UUID;

/**
 * One line of the Activity tab, shaped like the app's {@code ActivityItem}. {@code message} is a
 * continuation of the actor's name ("reacted 🔥 to your post"), with no leading capital.
 * {@code kind} is one of reaction, follow, reshare, comment, mention.
 */
public record ActivityItemResponse(
        UUID id,
        UUID actorId,
        String actorName,
        String actorHandle,
        String message,
        String timeAgo,
        Instant createdAt,
        String kind,
        String avatarUrl,
        boolean unread,
        UUID postId,
        UUID commentId) {
}
