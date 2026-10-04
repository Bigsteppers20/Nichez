package app.nichez.security;

import java.util.UUID;
import org.springframework.security.authentication.AuthenticationCredentialsNotFoundException;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;

/**
 * The only way to learn who is calling. Reads the user id from the verified
 * token. Never take the id of "my" resources from a path, query or body.
 */
public final class CurrentUser {

    private CurrentUser() {
    }

    /** The caller's user id. Throws an authentication error (401) when there is no verified token. */
    public static UUID id() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication instanceof JwtAuthenticationToken token && token.isAuthenticated()) {
            return UUID.fromString(token.getName());
        }
        throw new AuthenticationCredentialsNotFoundException("No authenticated user");
    }

    public static boolean isAdmin() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        return authentication != null && authentication.getAuthorities().stream()
                .anyMatch(a -> SupabaseJwtAuthenticationConverter.ROLE_ADMIN.equals(a.getAuthority()));
    }
}
