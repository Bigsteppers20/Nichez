package app.nichez.config;

import java.util.List;

/**
 * Startup configuration is missing or invalid. Carries one line per problem,
 * each naming a property and the rule it breaks, never the property's value.
 */
public class InvalidConfigurationException extends RuntimeException {

    private final List<String> activeProfiles;
    private final List<String> problems;

    public InvalidConfigurationException(List<String> activeProfiles, List<String> problems) {
        super("Invalid configuration (active profiles: " + activeProfiles + "):\n  - "
                + String.join("\n  - ", problems));
        this.activeProfiles = List.copyOf(activeProfiles);
        this.problems = List.copyOf(problems);
    }

    public List<String> activeProfiles() {
        return activeProfiles;
    }

    public List<String> problems() {
        return problems;
    }
}
