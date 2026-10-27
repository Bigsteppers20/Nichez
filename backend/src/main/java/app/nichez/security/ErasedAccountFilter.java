package app.nichez.security;

import app.nichez.common.ProblemFactory;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ProblemDetail;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * After the token is verified: a person whose account was erased gets 403 {@code ACCOUNT_DELETED}
 * on every call, even with a token that has not expired yet. Calls without a verified token pass
 * through untouched; the rest of the chain answers them.
 */
final class ErasedAccountFilter extends OncePerRequestFilter {

    static final String ACCOUNT_DELETED = "ACCOUNT_DELETED";

    private final ErasedAccounts erased;
    private final ObjectMapper mapper;

    ErasedAccountFilter(ErasedAccounts erased, ObjectMapper mapper) {
        this.erased = erased;
        this.mapper = mapper;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication instanceof JwtAuthenticationToken token && token.isAuthenticated()
                && isErased(token.getName())) {
            ProblemDetail problem = ProblemFactory.create(HttpStatus.FORBIDDEN, ACCOUNT_DELETED,
                    "This account has been deleted.", request);
            response.setStatus(HttpStatus.FORBIDDEN.value());
            response.setContentType(MediaType.APPLICATION_PROBLEM_JSON_VALUE);
            response.setCharacterEncoding("UTF-8");
            mapper.writeValue(response.getOutputStream(), problem);
            return;
        }
        chain.doFilter(request, response);
    }

    private boolean isErased(String subject) {
        try {
            return erased.isErased(UUID.fromString(subject));
        } catch (IllegalArgumentException e) {
            return false;
        }
    }
}
