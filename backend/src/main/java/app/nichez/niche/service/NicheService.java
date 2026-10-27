package app.nichez.niche.service;

import app.nichez.common.ApiException;
import app.nichez.common.ConflictException;
import app.nichez.common.CursorPage;
import app.nichez.common.NotFoundException;
import app.nichez.common.SlidingWindowRateLimiter;
import app.nichez.config.AppProperties;
import app.nichez.config.SocialProperties;
import app.nichez.niche.dto.NicheDtos.CreatedNiche;
import app.nichez.niche.dto.NicheDtos.Group;
import app.nichez.niche.dto.NicheDtos.Membership;
import app.nichez.niche.dto.NicheDtos.MyNiche;
import app.nichez.niche.dto.NicheDtos.Option;
import app.nichez.niche.repository.NicheCatalogRepository;
import app.nichez.niche.repository.NicheCatalogRepository.Niche;
import app.nichez.profile.repository.ProfileRepository;
import app.nichez.profile.service.HandleService;
import app.nichez.security.CurrentUser;
import java.text.Normalizer;
import java.time.Clock;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** The niche taxonomy, who belongs to which niche, and creating niches. Who is asking always comes from the token. */
@Service
public class NicheService {

    public static final String NICHE_NOT_FOUND = "NICHE_NOT_FOUND";
    public static final String NICHE_EXISTS = "NICHE_EXISTS";
    public static final String TOO_FEW_NICHES = "TOO_FEW_NICHES";
    public static final String ONE_NICHE_PER_GROUP = "ONE_NICHE_PER_GROUP";
    public static final String BANNED_FROM_NICHE = "BANNED_FROM_NICHE";

    private static final Pattern NON_SLUG = Pattern.compile("[^a-z0-9]+");

    private final NicheCatalogRepository niches;
    private final ProfileRepository profiles;
    private final SlidingWindowRateLimiter limiter;
    private final SocialProperties social;
    private final AppProperties app;
    private final Clock clock;

    NicheService(NicheCatalogRepository niches, ProfileRepository profiles, SlidingWindowRateLimiter limiter,
            SocialProperties social, AppProperties app, Clock clock) {
        this.niches = niches;
        this.profiles = profiles;
        this.limiter = limiter;
        this.social = social;
        this.app = app;
        this.clock = clock;
    }

    /** The groups and options the onboarding screens show, in the order they show them. */
    @Transactional(readOnly = true)
    public CursorPage<Group> taxonomy() {
        Map<String, List<Option>> options = new LinkedHashMap<>();
        Map<String, Boolean> strict = new HashMap<>();
        for (Niche niche : niches.taxonomy()) {
            options.computeIfAbsent(niche.groupName(), k -> new ArrayList<>()).add(new Option(niche.id(), niche.label()));
            strict.put(niche.groupName(), niche.strict());
        }
        List<Group> groups = options.entrySet().stream()
                .map(e -> new Group(e.getKey(), strict.get(e.getKey()), e.getValue()))
                .toList();
        return new CursorPage<>(groups, null);
    }

    @Transactional(readOnly = true)
    public CursorPage<MyNiche> mine() {
        List<MyNiche> items = niches.joinedBy(CurrentUser.id()).stream()
                .map(n -> new MyNiche(n.id(), n.label(), n.slug()))
                .toList();
        return new CursorPage<>(items, null);
    }

    /**
     * Replaces the caller's picks among the taxonomy niches. At least 3 (400 {@code TOO_FEW_NICHES});
     * at most one from a strict group such as Religion (400 {@code ONE_NICHE_PER_GROUP}); every id
     * must be a taxonomy niche (404 {@code NICHE_NOT_FOUND}). Niches the caller joined by other
     * means are kept. Completing the picks can finish onboarding.
     */
    @Transactional
    public CursorPage<MyNiche> setPicks(List<UUID> requested) {
        UUID me = CurrentUser.id();
        Set<UUID> picks = new LinkedHashSet<>(requested);
        List<Niche> found = niches.findAll(picks);
        if (found.size() != picks.size() || found.stream().anyMatch(n -> !n.system())) {
            throw notFound();
        }
        if (picks.size() < HandleService.MIN_NICHES_TO_ONBOARD) {
            throw new ApiException(HttpStatus.BAD_REQUEST, TOO_FEW_NICHES,
                    "Pick at least " + HandleService.MIN_NICHES_TO_ONBOARD + " niches.");
        }
        Map<String, Long> perStrictGroup = new HashMap<>();
        found.stream().filter(Niche::strict).forEach(n -> perStrictGroup.merge(n.groupName(), 1L, Long::sum));
        if (perStrictGroup.values().stream().anyMatch(count -> count > 1)) {
            throw new ApiException(HttpStatus.BAD_REQUEST, ONE_NICHE_PER_GROUP,
                    "Pick only one niche from a single-choice group.");
        }
        var now = clock.instant();
        profiles.ensureExists(me, now);
        Set<UUID> current = niches.joinedIds(me);
        Set<UUID> systemIds = new LinkedHashSet<>();
        niches.taxonomy().forEach(n -> systemIds.add(n.id()));
        List<UUID> toLeave = current.stream().filter(id -> systemIds.contains(id) && !picks.contains(id)).toList();
        niches.leaveAll(me, toLeave);
        for (UUID id : picks) {
            if (!current.contains(id)) {
                niches.join(me, id, now);
            }
        }
        profiles.markOnboardedIfComplete(me, HandleService.MIN_NICHES_TO_ONBOARD, now);
        return mine();
    }

    /** Joins a niche. Joining one already joined is not an error. */
    @Transactional
    public Membership join(UUID nicheId) {
        UUID me = CurrentUser.id();
        Niche niche = niches.find(nicheId).orElseThrow(NicheService::notFound);
        if (niches.isBanned(me, nicheId)) {
            throw new ApiException(HttpStatus.FORBIDDEN, BANNED_FROM_NICHE, "You cannot join this niche.");
        }
        profiles.ensureExists(me, clock.instant());
        niches.join(me, nicheId, clock.instant());
        profiles.markOnboardedIfComplete(me, HandleService.MIN_NICHES_TO_ONBOARD, clock.instant());
        return new Membership(true, niches.find(niche.id()).orElseThrow().memberCount());
    }

    /** Leaves a niche. Leaving one that was not joined is not an error. */
    @Transactional
    public Membership leave(UUID nicheId) {
        UUID me = CurrentUser.id();
        niches.find(nicheId).orElseThrow(NicheService::notFound);
        niches.leave(me, nicheId);
        return new Membership(false, niches.find(nicheId).orElseThrow().memberCount());
    }

    /**
     * Creates a niche from a name (the empty-search "create it" prompt). The creator joins it. A name
     * that maps to an existing niche is 409 {@code NICHE_EXISTS} with that niche's id in {@code nicheId}.
     */
    @Transactional
    public CreatedNiche create(String rawName) {
        UUID me = CurrentUser.id();
        limiter.check("niche-create:" + me, social.nichesPerDay(), Duration.ofDays(1));
        String label = rawName.strip().replaceAll("\\s+", " ");
        String slug = slugOf(label);
        if (slug.length() < 2) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "NICHE_NAME_INVALID", "Use letters or numbers in the name.");
        }
        var existing = niches.idBySlug(slug);
        if (existing.isPresent()) {
            throw new ConflictException(NICHE_EXISTS, "A niche with that name exists.")
                    .withProperty("nicheId", existing.get());
        }
        profiles.ensureExists(me, clock.instant());
        UUID id = niches.create(label, slug, me);
        niches.join(me, id, clock.instant());
        return new CreatedNiche(id, label, slug);
    }

    /** The address to share to invite people to a niche. */
    @Transactional(readOnly = true)
    public Map<String, String> inviteLink(UUID nicheId) {
        Niche niche = niches.find(nicheId).orElseThrow(NicheService::notFound);
        return Map.of("url", app.baseUrl() + "/e/" + niche.slug());
    }

    static String slugOf(String label) {
        String ascii = Normalizer.normalize(label, Normalizer.Form.NFD).replaceAll("\\p{M}+", "");
        String slug = NON_SLUG.matcher(ascii.toLowerCase(Locale.ROOT)).replaceAll("-");
        return slug.replaceAll("^-+|-+$", "");
    }

    private static NotFoundException notFound() {
        return new NotFoundException(NICHE_NOT_FOUND, "Niche not found.");
    }
}
