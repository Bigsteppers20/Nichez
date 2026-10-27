package app.nichez.profile.dto;

import java.util.UUID;

/**
 * A person as a list, a comment or a notification shows them. {@code avatarUrl} is a short-lived
 * signed URL (or null). Never carries a phone number or anything private (CLAUDE.md rule 11).
 */
public record UserBrief(UUID id, String name, String handle, String avatarUrl, boolean verified) {
}
