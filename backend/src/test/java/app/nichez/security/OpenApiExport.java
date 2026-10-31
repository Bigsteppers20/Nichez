package app.nichez.security;

import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import app.nichez.support.ApiTest;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders;

/**
 * Writes the OpenAPI document to {@code api-spec/} for scripts/export-api-spec.py. Skipped in the normal
 * suite; run with {@code -Dexport.api-spec=<dir> -Dtest=OpenApiExport}.
 */
@EnabledIfSystemProperty(named = "export.api-spec", matches = ".+")
@TestPropertySource(properties = {
        "springdoc.api-docs.enabled=true",
        "springdoc.swagger-ui.enabled=true"
})
class OpenApiExport extends ApiTest {

    @Test
    void writesTheOpenApiDocument() throws Exception {
        Path dir = Path.of(System.getProperty("export.api-spec"));
        Files.createDirectories(dir);
        write(dir.resolve("openapi.json"), "/v3/api-docs");
    }

    private void write(Path file, String url) throws Exception {
        byte[] body = mvc.perform(MockMvcRequestBuilders.get(url))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsByteArray();
        Files.writeString(file, new String(body, StandardCharsets.UTF_8));
    }
}
