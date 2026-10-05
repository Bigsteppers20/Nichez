package app.nichez.config;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/**
 * Where the backend calls Supabase Auth (GoTrue) server to server.
 * Env: SUPABASE_URL, SUPABASE_ANON_KEY.
 *
 * <p>The anon key is the project's public API key, sent as the {@code apikey}
 * header the Supabase gateway requires. It is not a user credential, but it is
 * still kept out of logs and out of {@link #toString()}.
 */
@Validated
@ConfigurationProperties("supabase")
public record SupabaseProperties(
        @NotBlank
        @Pattern(regexp = "^(https?://[^\\s/]\\S*[^\\s/])?$", message = "must be an http(s) URL without a trailing slash")
        String url,
        @NotBlank String anonKey) {

    @Override
    public String toString() {
        return "SupabaseProperties[url=<set>, anonKey=<redacted>]";
    }
}
