package app.nichez.security;

import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

/**
 * Whether a person's account has been erased. An erased person's sign-in record still exists at the
 * identity provider, so their token stays valid until it expires; this is how the API refuses it.
 */
@Component
class ErasedAccounts {

    private final JdbcClient jdbc;

    ErasedAccounts(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    boolean isErased(UUID userId) {
        return jdbc.sql("select deleted_at is not null from profiles where id = :id")
                .param("id", userId)
                .query(Boolean.class)
                .optional()
                .orElse(false);
    }
}
