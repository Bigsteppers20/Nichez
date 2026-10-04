package app.nichez.config;

import java.util.ArrayList;
import java.util.List;
import org.springframework.boot.context.properties.bind.BindException;
import org.springframework.boot.context.properties.bind.validation.BindValidationException;
import org.springframework.boot.diagnostics.FailureAnalysis;
import org.springframework.boot.diagnostics.FailureAnalyzer;
import org.springframework.core.Ordered;
import org.springframework.core.ResolvableType;
import org.springframework.validation.FieldError;
import org.springframework.validation.ObjectError;

/**
 * Turns configuration failures into a startup message that names every bad
 * property and why, but never prints a value. Runs before Spring Boot's own
 * analyzers, which would echo the rejected value (possibly a secret) into the log.
 *
 * <p>Registered in META-INF/spring.factories.
 */
public class ConfigFailureAnalyzer implements FailureAnalyzer, Ordered {

    static final String ACTION = "Set the missing or invalid values as environment variables (prod) or in "
            + "backend/.env (local profile). See backend/.env.example for every variable.";

    @Override
    public FailureAnalysis analyze(Throwable failure) {
        InvalidConfigurationException invalid = findCause(failure, InvalidConfigurationException.class);
        if (invalid != null) {
            return analysis("Invalid configuration (active profiles: " + invalid.activeProfiles() + "):",
                    invalid.problems(), invalid);
        }
        BindException bind = findCause(failure, BindException.class);
        if (bind != null) {
            return analysis("Invalid configuration:", problems(bind), bind);
        }
        return null;
    }

    private static FailureAnalysis analysis(String heading, List<String> problems, Throwable cause) {
        StringBuilder description = new StringBuilder(heading).append('\n');
        problems.forEach(p -> description.append("  - ").append(p).append('\n'));
        return new FailureAnalysis(description.toString(), ACTION, cause);
    }

    /** One line per problem in a binding failure, without values. */
    static List<String> problems(BindException ex) {
        String prefix = ex.getName().toString();
        List<String> problems = new ArrayList<>();
        if (ex.getCause() instanceof BindValidationException validation) {
            for (ObjectError error : validation.getValidationErrors().getAllErrors()) {
                problems.add(describe(prefix, error));
            }
        } else {
            ResolvableType type = ex.getTarget().getType();
            String target = type.resolve() != null ? type.resolve().getSimpleName() : type.toString();
            problems.add(prefix + ": value could not be converted to " + target);
        }
        return problems;
    }

    static String describe(String prefix, ObjectError error) {
        String message = error.getDefaultMessage();
        // Cross-field checks already name their property in the message.
        if (message != null && message.startsWith(prefix + ".")) {
            return message;
        }
        String property = prefix;
        if (error instanceof FieldError field) {
            String name = toKebab(field.getField());
            property = name.startsWith(prefix + ".") ? name : prefix + "." + name;
        }
        return property + ": " + message;
    }

    private static String toKebab(String camel) {
        return camel.replaceAll("([a-z0-9])([A-Z])", "$1-$2").toLowerCase();
    }

    private static <T extends Throwable> T findCause(Throwable failure, Class<T> type) {
        for (Throwable t = failure; t != null; t = t.getCause()) {
            if (type.isInstance(t)) {
                return type.cast(t);
            }
        }
        return null;
    }

    @Override
    public int getOrder() {
        return Ordered.HIGHEST_PRECEDENCE;
    }
}
