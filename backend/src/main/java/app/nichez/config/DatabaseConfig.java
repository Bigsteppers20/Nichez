package app.nichez.config;

import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.jdbc.JdbcConnectionDetails;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Feeds the validated {@link DbProperties} into Spring Boot's DataSource
 * auto-configuration. Backs off when a test supplies its own connection
 * (Testcontainers {@code @ServiceConnection}).
 */
@Configuration(proxyBeanMethods = false)
class DatabaseConfig {

    @Bean
    @ConditionalOnMissingBean(JdbcConnectionDetails.class)
    JdbcConnectionDetails jdbcConnectionDetails(DbProperties db) {
        return new JdbcConnectionDetails() {
            @Override
            public String getUsername() {
                return db.username();
            }

            @Override
            public String getPassword() {
                return db.password();
            }

            @Override
            public String getJdbcUrl() {
                return db.url();
            }
        };
    }
}
