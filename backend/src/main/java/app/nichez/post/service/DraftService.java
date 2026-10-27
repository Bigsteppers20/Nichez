package app.nichez.post.service;

import app.nichez.common.ApiException;
import app.nichez.common.Cursor;
import app.nichez.common.NotFoundException;
import app.nichez.common.Paging;
import app.nichez.common.TimeAgo;
import app.nichez.media.entity.Media;
import app.nichez.media.repository.MediaRepository;
import app.nichez.media.service.MediaUrlProvider;
import app.nichez.niche.repository.NicheRepository;
import app.nichez.niche.service.NicheService;
import app.nichez.post.dto.PostExtrasDtos.DraftRequest;
import app.nichez.post.dto.PostExtrasDtos.DraftResponse;
import app.nichez.post.dto.PostExtrasDtos.DraftsPage;
import app.nichez.post.repository.DraftRepository;
import app.nichez.post.repository.DraftRepository.Row;
import app.nichez.profile.repository.ProfileRepository;
import app.nichez.security.CurrentUser;
import java.time.Clock;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Unfinished posts. A draft is private to its owner; someone else's id is the same 404 {@code DRAFT_NOT_FOUND}. */
@Service
public class DraftService {

    public static final String DRAFT_NOT_FOUND = "DRAFT_NOT_FOUND";
    public static final String TOO_MANY_DRAFTS = "TOO_MANY_DRAFTS";
    public static final int MAX_DRAFTS = 50;

    private final DraftRepository drafts;
    private final MediaRepository media;
    private final NicheRepository niches;
    private final ProfileRepository profiles;
    private final MediaUrlProvider urls;
    private final Clock clock;

    DraftService(DraftRepository drafts, MediaRepository media, NicheRepository niches, ProfileRepository profiles,
            MediaUrlProvider urls, Clock clock) {
        this.drafts = drafts;
        this.media = media;
        this.niches = niches;
        this.profiles = profiles;
        this.urls = urls;
        this.clock = clock;
    }

    @Transactional
    public DraftResponse create(DraftRequest request) {
        UUID me = CurrentUser.id();
        if (drafts.count(me) >= MAX_DRAFTS) {
            throw new ApiException(HttpStatus.CONFLICT, TOO_MANY_DRAFTS, "You can keep up to " + MAX_DRAFTS + " drafts.");
        }
        if (request.mediaId() != null) {
            media.findByIdAndOwnerId(request.mediaId(), me)
                    .orElseThrow(() -> new NotFoundException("MEDIA_NOT_FOUND", "Media not found."));
        }
        if (request.nicheId() != null && !niches.exists(request.nicheId())) {
            throw new NotFoundException(NicheService.NICHE_NOT_FOUND, "Niche not found.");
        }
        profiles.ensureExists(me, clock.instant());
        UUID id = drafts.insert(me, request.mediaId(), request.nicheId(),
                request.caption() == null || request.caption().isBlank() ? null : request.caption(),
                request.audience() == null ? "everyone" : request.audience(),
                request.allowComments() == null || request.allowComments());
        return drafts.list(me, null, null, MAX_DRAFTS).stream().filter(r -> r.id().equals(id)).findFirst()
                .map(this::toDto).orElseThrow();
    }

    @Transactional(readOnly = true)
    public DraftsPage list(String cursor, Integer requestedLimit) {
        UUID me = CurrentUser.id();
        int limit = Paging.limit(requestedLimit);
        Cursor.TimeId after = Cursor.TimeId.decode(cursor);
        List<Row> fetched = drafts.list(me, after == null ? null : after.time(), after == null ? null : after.id(), limit + 1);
        List<DraftResponse> items = Paging.head(fetched, limit).stream().map(this::toDto).toList();
        return new DraftsPage(items, Paging.nextCursor(fetched, limit, r -> new Cursor.TimeId(r.updatedAt(), r.id()).encode()),
                drafts.count(me));
    }

    @Transactional
    public void delete(UUID id) {
        if (!drafts.delete(CurrentUser.id(), id)) {
            throw new NotFoundException(DRAFT_NOT_FOUND, "Draft not found.");
        }
    }

    private DraftResponse toDto(Row row) {
        return new DraftResponse(row.id(), row.mediaId(), urls.urlFor(row.mediaKey()), row.nicheId(), row.caption(),
                row.audience(), row.allowComments(), row.updatedAt(), TimeAgo.between(row.updatedAt(), clock.instant()));
    }
}
