package app.nichez.post.entity;

import app.nichez.common.AssignedIdEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Convert;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.validation.constraints.Size;
import java.time.Instant;
import java.util.UUID;

/**
 * A post in a niche. It is {@link PostStatus#PROCESSING} until its media is ready. Posts are
 * never deleted from the table: removal and deletion are statuses with a timestamp.
 *
 * <p>{@code reactionCount} and {@code commentCount} have no setters on purpose. They change only
 * through single atomic UPDATE statements, so two simultaneous reactions cannot overwrite each other.
 */
@Entity
@Table(name = "posts")
public class Post extends AssignedIdEntity<UUID> {

    public static final int MAX_CAPTION_LENGTH = 2200;

    @Id
    private UUID id;

    @Column(name = "author_id", nullable = false, updatable = false)
    private UUID authorId;

    @Column(name = "niche_id", nullable = false, updatable = false)
    private UUID nicheId;

    @Column(name = "media_id", nullable = false, updatable = false)
    private UUID mediaId;

    @Size(max = MAX_CAPTION_LENGTH)
    private String caption;

    @Convert(converter = AudienceConverter.class)
    @Column(nullable = false)
    private Audience audience;

    @Column(name = "allow_comments", nullable = false)
    private boolean allowComments;

    @Column(nullable = false)
    private boolean sensitive;

    @Column(name = "sensitive_reason")
    private String sensitiveReason;

    @Convert(converter = PostStatusConverter.class)
    @Column(nullable = false)
    private PostStatus status;

    @Column(name = "reaction_count", nullable = false, insertable = false, updatable = false)
    private int reactionCount;

    @Column(name = "comment_count", nullable = false, insertable = false, updatable = false)
    private int commentCount;

    /** The community this post was made in, or null. Its niche is then the community's home niche. */
    @Column(name = "community_id", updatable = false)
    private UUID communityId;

    /** The post this one reshares, or null. */
    @Column(name = "reshare_of", updatable = false)
    private UUID reshareOf;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "published_at")
    private Instant publishedAt;

    @Column(name = "deleted_at")
    private Instant deletedAt;

    protected Post() {
    }

    public Post(UUID id, UUID authorId, UUID nicheId, UUID mediaId, String caption, Audience audience,
            boolean allowComments, Instant now) {
        this.id = id;
        this.authorId = authorId;
        this.nicheId = nicheId;
        this.mediaId = mediaId;
        this.caption = caption;
        this.audience = audience;
        this.allowComments = allowComments;
        this.status = PostStatus.PROCESSING;
        this.createdAt = now;
    }

    @Override
    public UUID getId() {
        return id;
    }

    public UUID getAuthorId() {
        return authorId;
    }

    public UUID getNicheId() {
        return nicheId;
    }

    public UUID getMediaId() {
        return mediaId;
    }

    public String getCaption() {
        return caption;
    }

    public void setCaption(String caption) {
        this.caption = caption;
    }

    public Audience getAudience() {
        return audience;
    }

    public void setAudience(Audience audience) {
        this.audience = audience;
    }

    public boolean isAllowComments() {
        return allowComments;
    }

    public void setAllowComments(boolean allowComments) {
        this.allowComments = allowComments;
    }

    public boolean isSensitive() {
        return sensitive;
    }

    public String getSensitiveReason() {
        return sensitiveReason;
    }

    /** Marks the post sensitive (or clears it), with the reason shown on the veil. */
    public void setSensitive(boolean sensitive, String reason) {
        this.sensitive = sensitive;
        this.sensitiveReason = sensitive ? reason : null;
    }

    public PostStatus getStatus() {
        return status;
    }

    public int getReactionCount() {
        return reactionCount;
    }

    public int getCommentCount() {
        return commentCount;
    }

    public UUID getCommunityId() {
        return communityId;
    }

    /** Set before the post is first saved; a post never moves to another community. */
    public void setCommunityId(UUID communityId) {
        this.communityId = communityId;
    }

    public UUID getReshareOf() {
        return reshareOf;
    }

    public void setReshareOf(UUID reshareOf) {
        this.reshareOf = reshareOf;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getPublishedAt() {
        return publishedAt;
    }

    public Instant getDeletedAt() {
        return deletedAt;
    }

    /** Media is ready: the post goes live and takes its place in feeds from {@code now}. */
    public void publish(Instant now) {
        this.status = PostStatus.PUBLISHED;
        this.publishedAt = now;
    }

    public void markFailed() {
        this.status = PostStatus.FAILED;
    }

    /** Moderators took it down. {@code deletedAt} is stamped too: it starts the media retention clock. */
    public void markRemoved(Instant now) {
        this.status = PostStatus.REMOVED;
        this.deletedAt = now;
    }

    public void markDeleted(Instant now) {
        this.status = PostStatus.DELETED;
        this.deletedAt = now;
    }
}
