package app.nichez.security;

import app.nichez.config.CorsProperties;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Duration;
import java.util.List;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;

/**
 * Resource server for Supabase JWTs. Deny by default: a route is reachable
 * without a token only if it is listed in {@link #PUBLIC_PATHS}.
 *
 * <p>Stateless, so there is no session cookie for CSRF to protect; CSRF is
 * off because every call carries a bearer token in a header.
 */
@Configuration(proxyBeanMethods = false)
@EnableWebSecurity
@EnableMethodSecurity
class SecurityConfig {

    /** Add to this list only for endpoints that are public on purpose. */
    static final String[] PUBLIC_PATHS = {
            "/api/v1/ping",
            "/actuator/health",
            "/actuator/health/**",
    };

    @Bean
    SecurityFilterChain securityFilterChain(HttpSecurity http, ObjectMapper mapper) throws Exception {
        http
                .csrf(AbstractHttpConfigurer::disable)
                .cors(Customizer.withDefaults())
                .sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(auth -> auth
                        .requestMatchers(PUBLIC_PATHS).permitAll()
                        .requestMatchers("/api/v1/admin/**").hasRole("ADMIN")
                        .anyRequest().authenticated())
                .oauth2ResourceServer(oauth -> oauth
                        .jwt(jwt -> jwt.jwtAuthenticationConverter(new SupabaseJwtAuthenticationConverter()))
                        .authenticationEntryPoint(ProblemSecurityHandlers.entryPoint(mapper))
                        .accessDeniedHandler(ProblemSecurityHandlers.accessDeniedHandler(mapper)))
                .exceptionHandling(e -> e
                        .authenticationEntryPoint(ProblemSecurityHandlers.entryPoint(mapper))
                        .accessDeniedHandler(ProblemSecurityHandlers.accessDeniedHandler(mapper)));
        return http.build();
    }

    /** Exact origins from CORS_ALLOWED_ORIGINS. With none configured, no CORS headers are sent. */
    @Bean
    CorsConfigurationSource corsConfigurationSource(CorsProperties cors) {
        UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
        if (!cors.allowedOrigins().isEmpty()) {
            CorsConfiguration config = new CorsConfiguration();
            config.setAllowedOrigins(cors.allowedOrigins());
            config.setAllowedMethods(List.of("GET", "POST", "PUT", "PATCH", "DELETE", "OPTIONS"));
            config.setAllowedHeaders(List.of("Authorization", "Content-Type", "Idempotency-Key", "X-Request-Id"));
            config.setExposedHeaders(List.of("X-Request-Id", "Retry-After"));
            config.setAllowCredentials(false);
            config.setMaxAge(Duration.ofHours(1));
            source.registerCorsConfiguration("/**", config);
        }
        return source;
    }
}
