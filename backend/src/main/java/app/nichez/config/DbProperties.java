package app.nichez.config;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/** Postgres connection. Env: DB_URL, DB_USERNAME, DB_PASSWORD. */
@Validated
@ConfigurationProperties("db")
public record DbProperties(
        @NotBlank
        @Pattern(regexp = "^(jdbc:postgresql://\\S+)?$", message = "must be a jdbc:postgresql:// URL")
        String url,
        @NotBlank String username,
        @NotBlank String password) {

    @Override
    public String toString() {
        return "DbProperties[url=<set>, username=<set>, password=<redacted>]";
    }
}
