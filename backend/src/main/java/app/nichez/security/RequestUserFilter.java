package app.nichez.security;

import app.nichez.common.RequestLogFilter;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.UUID;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * After the token is verified and the account is known to be live: remembers who is calling on the
 * request, so the request log (which runs outside Spring Security and cannot see the security context
 * any more by the time the call ends) can say. Placed after {@link ErasedAccountFilter}, so a person
 * whose account was erased is never attributed again.
 */
final class RequestUserFilter extends OncePerRequestFilter {

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication instanceof JwtAuthenticationToken token && token.isAuthenticated()) {
            try {
                request.setAttribute(RequestLogFilter.USER_ATTRIBUTE, UUID.fromString(token.getName()));
            } catch (IllegalArgumentException ignored) {
                // The converter already refuses a subject that is not a UUID; nothing to record.
            }
        }
        chain.doFilter(request, response);
    }
}
