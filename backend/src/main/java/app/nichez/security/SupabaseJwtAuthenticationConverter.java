package app.nichez.security;

import java.util.List;
import java.util.Map;
import org.springframework.core.convert.converter.Converter;
import org.springframework.security.authentication.AbstractAuthenticationToken;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;

/**
 * Turns a verified Supabase token into an authentication.
 * <ul>
 *   <li>The principal name is {@code sub}, the user's UUID (checked by the decoder).</li>
 *   <li>{@code ROLE_ADMIN} is granted only when {@code app_metadata.role} is "admin".
 *       {@code app_metadata} can be written only by the server, never by the user.</li>
 *   <li>{@code user_metadata} is client-writable and is never read here.</li>
 * </ul>
 */
public class SupabaseJwtAuthenticationConverter implements Converter<Jwt, AbstractAuthenticationToken> {

    static final String ROLE_USER = "ROLE_USER";
    static final String ROLE_ADMIN = "ROLE_ADMIN";

    @Override
    public AbstractAuthenticationToken convert(Jwt jwt) {
        List<GrantedAuthority> authorities = isAdmin(jwt)
                ? List.of(new SimpleGrantedAuthority(ROLE_USER), new SimpleGrantedAuthority(ROLE_ADMIN))
                : List.of(new SimpleGrantedAuthority(ROLE_USER));
        return new JwtAuthenticationToken(jwt, authorities, jwt.getSubject());
    }

    private static boolean isAdmin(Jwt jwt) {
        Object appMetadata = jwt.getClaim("app_metadata");
        return appMetadata instanceof Map<?, ?> map && "admin".equals(map.get("role"));
    }
}
