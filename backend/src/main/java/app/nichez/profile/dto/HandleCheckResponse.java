package app.nichez.profile.dto;

import java.util.List;

/** {@code suggestions} is empty when the handle is available, otherwise up to three free alternatives. */
public record HandleCheckResponse(boolean available, List<String> suggestions) {
}
