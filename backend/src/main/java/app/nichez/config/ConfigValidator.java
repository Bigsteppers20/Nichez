package app.nichez.config;

import jakarta.validation.Validation;
import jakarta.validation.ValidatorFactory;
import java.util.ArrayList;
import java.util.List;
import org.springframework.boot.context.event.ApplicationEnvironmentPreparedEvent;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.BindException;
import org.springframework.boot.context.properties.bind.Bindable;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.context.properties.bind.validation.ValidationBindHandler;
import org.springframework.context.ApplicationListener;
import org.springframework.core.Ordered;
import org.springframework.validation.beanvalidation.SpringValidatorAdapter;

/**
 * Validates every configuration record as soon as the environment is ready
 * (profile chosen, .env imported for local), before any bean is created.
 *
 * <p>Without this, Spring creates beans in its own order: a bad auth secret
 * could surface only as a database connection error, and only the first bad
 * group would be reported. Here every problem is collected and reported at once.
 *
 * <p>Registered in META-INF/spring.factories.
 */
public class ConfigValidator implements ApplicationListener<ApplicationEnvironmentPreparedEvent>, Ordered {

    static final List<Class<?>> PROPERTY_RECORDS = List.of(
            DbProperties.class,
            AuthProperties.class,
            SmsAuthProperties.class,
            SupabaseProperties.class,
            ProfileProperties.class,
            PostProperties.class,
            AppProperties.class,
            SocialProperties.class,
            AccountProperties.class,
            UploadProperties.class,
            StorageProperties.class,
            MediaUrlProperties.class,
            CorsProperties.class,
            RateLimitProperties.class);

    @Override
    public void onApplicationEvent(ApplicationEnvironmentPreparedEvent event) {
        List<String> problems = validate(Binder.get(event.getEnvironment()));
        if (!problems.isEmpty()) {
            throw new InvalidConfigurationException(
                    List.of(event.getEnvironment().getActiveProfiles()), problems);
        }
    }

    static List<String> validate(Binder binder) {
        List<String> problems = new ArrayList<>();
        try (ValidatorFactory factory = Validation.buildDefaultValidatorFactory()) {
            SpringValidatorAdapter validator = new SpringValidatorAdapter(factory.getValidator());
            for (Class<?> record : PROPERTY_RECORDS) {
                String prefix = record.getAnnotation(ConfigurationProperties.class).value();
                try {
                    // A fresh handler per record: it accumulates errors internally.
                    binder.bindOrCreate(prefix, Bindable.of(record), new ValidationBindHandler(validator));
                } catch (BindException ex) {
                    problems.addAll(ConfigFailureAnalyzer.problems(ex).stream().sorted().toList());
                }
            }
        }
        return problems;
    }

    @Override
    public int getOrder() {
        // After Spring Boot's own environment post-processing (config files, .env import).
        return Ordered.LOWEST_PRECEDENCE;
    }
}
