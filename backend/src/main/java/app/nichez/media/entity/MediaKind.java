package app.nichez.media.entity;

import app.nichez.common.DbValue;

/** What was uploaded. Stored as {@code image} or {@code video}. */
public enum MediaKind implements DbValue {
    IMAGE, VIDEO
}
