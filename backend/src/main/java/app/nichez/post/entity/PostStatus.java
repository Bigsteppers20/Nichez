package app.nichez.post.entity;

import app.nichez.common.DbValue;

/**
 * A post is PROCESSING until its media is ready, then PUBLISHED. FAILED means processing broke,
 * REMOVED means moderators took it down, DELETED means its author did (a soft delete).
 */
public enum PostStatus implements DbValue {
    PROCESSING, PUBLISHED, FAILED, REMOVED, DELETED
}
