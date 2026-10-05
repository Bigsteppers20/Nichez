package app.nichez.media.entity;

import app.nichez.common.DbValue;

/** Where an upload is in its life: asked for, received, being processed, usable, or broken. */
public enum MediaStatus implements DbValue {
    PENDING, UPLOADED, PROCESSING, READY, FAILED
}
