package app.nichez.activity.service;

import app.nichez.activity.repository.NotificationRepository;
import app.nichez.activity.repository.PushPreferencesRepository;
import app.nichez.activity.repository.PushPreferencesRepository.Settings;
import app.nichez.niche.repository.NicheRepository;
import app.nichez.post.entity.Post;
import app.nichez.profile.dto.UserBrief;
import app.nichez.profile.repository.UserRepository;
import app.nichez.profile.service.UserBriefs;
import app.nichez.safety.service.BlockPolicy;
import java.time.Clock;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * Tells a person that someone did something to their content. Feature services call this in the
 * same transaction as the action, so a notification exists only if the action does.
 *
 * <p>Nobody is notified about their own actions, by someone they have a block with either way, or
 * when their account is deactivated. The push goes out after the transaction commits, to the
 * recipient's devices, only if their settings allow that kind. A failure here never fails the
 * action that caused it.
 */
@Component
public class Notifier {

    private static final Logger log = LoggerFactory.getLogger(Notifier.class);

    private static final Map<String, String> EMOJI = Map.of("love", "❤️", "laugh", "😂", "fire", "🔥",
            "cry", "😭", "clap", "👏", "dead", "💀");

    private final NotificationRepository notifications;
    private final PushPreferencesRepository preferences;
    private final PushSender sender;
    private final BlockPolicy blocks;
    private final UserRepository users;
    private final UserBriefs briefs;
    private final NicheRepository niches;
    private final Clock clock;

    Notifier(NotificationRepository notifications, PushPreferencesRepository preferences, PushSender sender,
            BlockPolicy blocks, UserRepository users, UserBriefs briefs, NicheRepository niches, Clock clock) {
        this.notifications = notifications;
        this.preferences = preferences;
        this.sender = sender;
        this.blocks = blocks;
        this.users = users;
        this.briefs = briefs;
        this.niches = niches;
        this.clock = clock;
    }

    public void reacted(UUID actor, Post post, String reactionKind) {
        notify(actor, post.getAuthorId(), "reaction", post, null,
                "reacted " + EMOJI.getOrDefault(reactionKind, "") + " to your post");
    }

    public void followed(UUID actor, UUID followee) {
        notify(actor, followee, "follow", null, null, "started following you");
    }

    public void reshared(UUID actor, Post post) {
        notify(actor, post.getAuthorId(), "reshare", post, null, "reshared your post");
    }

    public void commented(UUID actor, Post post, UUID commentId) {
        notify(actor, post.getAuthorId(), "comment", post, commentId, "commented on your post");
    }

    public void repliedTo(UUID actor, UUID parentAuthor, Post post, UUID commentId) {
        notify(actor, parentAuthor, "comment", post, commentId, "replied to your comment");
    }

    public void mentioned(UUID actor, UUID mentioned, Post post, UUID commentId) {
        notify(actor, mentioned, "mention", post, commentId, "mentioned you in a comment");
    }

    private void notify(UUID actor, UUID recipient, String kind, Post post, UUID commentId, String message) {
        if (actor.equals(recipient) || blocks.isBlockedEitherWay(actor, recipient) || !users.isActive(recipient)) {
            return;
        }
        UUID nicheId = post == null ? null : post.getNicheId();
        UUID postId = post == null ? null : post.getId();
        if (!notifications.insert(recipient, actor, kind, postId, commentId, nicheId, message, clock.instant())) {
            return;
        }
        afterCommit(() -> push(actor, recipient, kind, nicheId, message));
    }

    private void push(UUID actor, UUID recipient, String kind, UUID nicheId, String message) {
        try {
            Settings settings = preferences.settings(recipient);
            if (!settings.allows(kind)) {
                return;
            }
            List<String> tokens = preferences.tokensOf(recipient);
            if (tokens.isEmpty()) {
                return;
            }
            UserBrief who = briefs.load(List.of(actor)).get(actor);
            String name = who == null || who.name() == null ? (who == null ? null : who.handle()) : who.name();
            String body = (name == null ? "Someone" : name) + " " + message;
            String title = nicheId == null ? "Nichez" : niches.labelOf(nicheId).orElse("Nichez");
            tokens.forEach(token -> sender.send(token, title, body));
        } catch (RuntimeException e) {
            // Class name only: the message can carry a device token or the text of the push.
            log.warn("Push could not be sent, error={}", e.getClass().getSimpleName());
        }
    }

    private static void afterCommit(Runnable action) {
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    action.run();
                }
            });
        } else {
            action.run();
        }
    }
}
