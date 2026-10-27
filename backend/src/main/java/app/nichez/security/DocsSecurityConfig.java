package app.nichez.security;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;

/**
 * Lets the Swagger UI page and the OpenAPI document load without a token. Present only when
 * {@code springdoc.swagger-ui.enabled=true}, which only application-local.yml sets, so a deployed
 * server never opens these paths and keeps answering 401 for them.
 *
 * <p>This is a separate chain on purpose: {@link SecurityConfig#PUBLIC_PATHS} lists what is public in every
 * environment, and the API's own routes stay behind the JWT even locally. Swagger's "Try it out" sends the
 * token the person pastes into Authorize, so the endpoints are tested exactly as the app would call them.
 */
@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(prefix = "springdoc.swagger-ui", name = "enabled", havingValue = "true")
class DocsSecurityConfig {

    @Bean
    @Order(Ordered.HIGHEST_PRECEDENCE)
    SecurityFilterChain docsSecurityFilterChain(HttpSecurity http) throws Exception {
        http
                .securityMatcher("/swagger-ui.html", "/swagger-ui/**", "/v3/api-docs", "/v3/api-docs/**")
                .csrf(AbstractHttpConfigurer::disable)
                .sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(auth -> auth.anyRequest().permitAll());
        return http.build();
    }
}
