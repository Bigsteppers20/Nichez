package app.nichez.config;

import io.swagger.v3.oas.models.Components;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.security.SecurityRequirement;
import io.swagger.v3.oas.models.security.SecurityScheme;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Describes the API to Swagger UI and adds the Authorize button for the Supabase access token
 * (the {@code accessToken} that {@code POST /api/v1/auth/phone/verify} returns). Local only, like Swagger itself.
 */
@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(prefix = "springdoc.swagger-ui", name = "enabled", havingValue = "true")
class OpenApiConfig {

    private static final String SCHEME = "supabaseJwt";

    @Bean
    OpenAPI nichezOpenApi() {
        return new OpenAPI()
                .info(new Info()
                        .title("Nichez API")
                        .description("Local testing only. Sign in with POST /api/v1/auth/phone/start and /verify, "
                                + "then paste the accessToken into Authorize (without the word Bearer).")
                        .version("v1"))
                .components(new Components().addSecuritySchemes(SCHEME, new SecurityScheme()
                        .type(SecurityScheme.Type.HTTP)
                        .scheme("bearer")
                        .bearerFormat("JWT")))
                .addSecurityItem(new SecurityRequirement().addList(SCHEME));
    }
}
