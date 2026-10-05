package app.nichez.profile.service;

import app.nichez.profile.repository.ProfileRepository;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;
import org.springframework.stereotype.Component;

/**
 * Three free alternatives to a handle: the input with digits appended, or with a niche word
 * (the user's own niches first, otherwise the system niches). Every suggestion passes
 * {@link HandleRules} and is not held by anyone else.
 */
@Component
class HandleSuggester {

    static final int WANTED = 3;

    private static final int ROUNDS = 4;
    private static final int CANDIDATES_PER_ROUND = 12;
    private static final int MAX_BASE_LENGTH = 20;
    private static final int MAX_WORDS = 8;
    private static final String FALLBACK_BASE = "user";

    private final ProfileRepository profiles;

    HandleSuggester(ProfileRepository profiles) {
        this.profiles = profiles;
    }

    List<String> suggest(String input, UUID userId, Collection<String> reserved, Collection<String> verified) {
        String base = baseOf(input);
        List<String> words = nicheWords(userId);
        Set<String> chosen = new LinkedHashSet<>();
        ThreadLocalRandom random = ThreadLocalRandom.current();

        for (int round = 0; round < ROUNDS && chosen.size() < WANTED; round++) {
            Set<String> candidates = new LinkedHashSet<>();
            for (int i = 0; i < CANDIDATES_PER_ROUND; i++) {
                String word = words.isEmpty() ? null : words.get(random.nextInt(words.size()));
                String candidate = switch (i % 3) {
                    case 0 -> base + digits(random, 2);
                    case 1 -> word != null ? base + "_" + word : base + digits(random, 3);
                    default -> word != null ? base + word + digits(random, 2) : base + "_" + digits(random, 2);
                };
                if (HandleRules.check(candidate, reserved, verified).isEmpty()
                        && !candidate.equalsIgnoreCase(input)) {
                    candidates.add(candidate);
                }
            }
            Set<String> taken = profiles.takenAmong(candidates, userId);
            for (String candidate : candidates) {
                if (chosen.size() < WANTED && !taken.contains(candidate.toLowerCase(Locale.ROOT))) {
                    chosen.add(candidate);
                }
            }
        }
        return List.copyOf(chosen);
    }

    /** What the user typed, reduced to handle characters and shortened to leave room for a suffix. */
    private static String baseOf(String input) {
        String cleaned = input == null ? "" : input.replaceAll("[^a-zA-Z0-9_.]", "").toLowerCase(Locale.ROOT);
        cleaned = cleaned.replaceAll("^[._]+|[._]+$", "");
        if (cleaned.length() > MAX_BASE_LENGTH) {
            cleaned = cleaned.substring(0, MAX_BASE_LENGTH);
        }
        return cleaned.isEmpty() ? FALLBACK_BASE : cleaned;
    }

    private List<String> nicheWords(UUID userId) {
        List<String> slugs = profiles.nicheSlugsOf(userId);
        if (slugs.isEmpty()) {
            slugs = profiles.systemNicheSlugs();
        }
        List<String> words = new ArrayList<>();
        for (String slug : slugs) {
            String word = slug.replaceAll("[^a-zA-Z0-9]", "").toLowerCase(Locale.ROOT);
            if (!word.isEmpty()) {
                words.add(word);
            }
        }
        Collections.shuffle(words);
        return words.size() > MAX_WORDS ? words.subList(0, MAX_WORDS) : words;
    }

    private static String digits(ThreadLocalRandom random, int count) {
        StringBuilder sb = new StringBuilder(count);
        for (int i = 0; i < count; i++) {
            sb.append(random.nextInt(10));
        }
        return sb.toString();
    }
}
