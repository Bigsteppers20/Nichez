package app.nichez.post.entity;

import app.nichez.common.DbValue;

/** Who can see a post: everyone, or only members of the niche it was posted to ({@code niche_members}). */
public enum Audience implements DbValue {
    EVERYONE, NICHE_MEMBERS
}
