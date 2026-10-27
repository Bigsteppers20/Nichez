package app.nichez.security;

import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import app.nichez.support.ApiTest;
import org.junit.jupiter.api.Test;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders;

/** What a developer sees with the switch application-local.yml flips on. The default (off) is in SecurityIntegrationTests. */
@TestPropertySource(properties = {
        "springdoc.api-docs.enabled=true",
        "springdoc.swagger-ui.enabled=true"
})
class OpenApiDocsTests extends ApiTest {

    @Test
    void theOpenApiDocumentLoadsWithoutATokenAndListsTheApi() throws Exception {
        mvc.perform(MockMvcRequestBuilders.get("/v3/api-docs"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.paths['/api/v1/ping']").exists())
                .andExpect(jsonPath("$.paths['/api/v1/me/settings']").exists())
                .andExpect(jsonPath("$.paths['/api/v1/posts/{id}/reaction']").exists());
    }

    @Test
    void theDocumentOffersABearerTokenToPasteIntoAuthorize() throws Exception {
        mvc.perform(MockMvcRequestBuilders.get("/v3/api-docs"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.components.securitySchemes.supabaseJwt.type").value("http"))
                .andExpect(jsonPath("$.components.securitySchemes.supabaseJwt.scheme").value("bearer"))
                .andExpect(jsonPath("$.security[0].supabaseJwt").exists());
    }

    @Test
    void theSwaggerPageLoadsWithoutAToken() throws Exception {
        mvc.perform(MockMvcRequestBuilders.get("/swagger-ui/index.html")).andExpect(status().isOk());
    }

    @Test
    void theApiItselfStaysBehindTheTokenWhileDocsAreOn() throws Exception {
        mvc.perform(MockMvcRequestBuilders.get("/api/v1/me/settings"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("AUTHENTICATION_REQUIRED"));
    }
}
