package app.nichez.collection.service;

import app.nichez.collection.dto.CollectionDtos.CollectionResponse;
import app.nichez.collection.dto.CollectionDtos.SaveState;
import app.nichez.collection.repository.CollectionRepository;
import app.nichez.collection.repository.CollectionRepository.Row;
import app.nichez.common.ConflictException;
import app.nichez.common.Cursor;
import app.nichez.common.CursorPage;
import app.nichez.common.NotFoundException;
import app.nichez.common.Paging;
import app.nichez.post.dto.PostResponse;
import app.nichez.post.repository.PostListRepository;
import app.nichez.post.repository.PostListRepository.Item;
import app.nichez.post.service.PostAccess;
import app.nichez.post.service.PostPages;
import app.nichez.security.CurrentUser;
import java.time.Clock;
import java.util.List;
import java.util.UUID;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Saving posts and keeping them in collections. Everything is the caller's own: a collection that is
 * not theirs is the same 404 {@code COLLECTION_NOT_FOUND} as one that does not exist.
 */
@Service
public class CollectionService {

    public static final String COLLECTION_NOT_FOUND = "COLLECTION_NOT_FOUND";
    public static final String COLLECTION_EXISTS = "COLLECTION_EXISTS";

    private final CollectionRepository collections;
    private final PostListRepository lists;
    private final PostPages pages;
    private final PostAccess access;
    private final Clock clock;

    CollectionService(CollectionRepository collections, PostListRepository lists, PostPages pages, PostAccess access,
            Clock clock) {
        this.collections = collections;
        this.lists = lists;
        this.pages = pages;
        this.access = access;
        this.clock = clock;
    }

    /** Saves a post, and files it into {@code collectionId} when one is given. Saving twice is not an error. */
    @Transactional
    public SaveState save(UUID postId, UUID collectionId) {
        UUID me = CurrentUser.id();
        access.requireInteractable(me, postId);
        if (collectionId != null) {
            collections.find(me, collectionId).orElseThrow(CollectionService::notFound);
        }
        collections.save(me, postId, clock.instant());
        if (collectionId != null) {
            collections.addPost(collectionId, postId, clock.instant());
        }
        return new SaveState(true, collections.saveCount(postId));
    }

    /** Unsaves the post and takes it out of all the caller's collections. */
    @Transactional
    public SaveState unsave(UUID postId) {
        UUID me = CurrentUser.id();
        access.requireInteractable(me, postId);
        collections.unsave(me, postId);
        return new SaveState(false, collections.saveCount(postId));
    }

    /** Everything the caller saved, newest save first. */
    @Transactional(readOnly = true)
    public CursorPage<PostResponse> saved(String cursor, Integer requestedLimit) {
        UUID me = CurrentUser.id();
        int limit = Paging.limit(requestedLimit);
        List<Item> fetched = lists.saved(me, Cursor.TimeId.decode(cursor), limit + 1);
        return pages.page(me, fetched, limit);
    }

    @Transactional(readOnly = true)
    public CursorPage<CollectionResponse> list() {
        List<CollectionResponse> items = collections.list(CurrentUser.id()).stream().map(CollectionService::toDto).toList();
        return new CursorPage<>(items, null);
    }

    @Transactional
    public CollectionResponse create(String name) {
        UUID me = CurrentUser.id();
        try {
            UUID id = collections.create(me, name.strip());
            return toDto(collections.find(me, id).orElseThrow());
        } catch (DuplicateKeyException e) {
            throw exists();
        }
    }

    @Transactional
    public CollectionResponse rename(UUID id, String name) {
        UUID me = CurrentUser.id();
        try {
            if (!collections.rename(me, id, name.strip())) {
                throw notFound();
            }
        } catch (DuplicateKeyException e) {
            throw exists();
        }
        return toDto(collections.find(me, id).orElseThrow());
    }

    /** Deletes the collection. The posts stay saved. */
    @Transactional
    public void delete(UUID id) {
        if (!collections.delete(CurrentUser.id(), id)) {
            throw notFound();
        }
    }

    @Transactional(readOnly = true)
    public CursorPage<PostResponse> posts(UUID id, String cursor, Integer requestedLimit) {
        UUID me = CurrentUser.id();
        collections.find(me, id).orElseThrow(CollectionService::notFound);
        int limit = Paging.limit(requestedLimit);
        List<Item> fetched = lists.inCollection(me, id, Cursor.TimeId.decode(cursor), limit + 1);
        return pages.page(me, fetched, limit);
    }

    /** Files a post into a collection; the post becomes saved too. */
    @Transactional
    public void addPost(UUID collectionId, UUID postId) {
        UUID me = CurrentUser.id();
        collections.find(me, collectionId).orElseThrow(CollectionService::notFound);
        access.requireInteractable(me, postId);
        collections.save(me, postId, clock.instant());
        collections.addPost(collectionId, postId, clock.instant());
    }

    /** Takes a post out of the collection; it stays saved. */
    @Transactional
    public void removePost(UUID collectionId, UUID postId) {
        collections.find(CurrentUser.id(), collectionId).orElseThrow(CollectionService::notFound);
        collections.removePost(collectionId, postId);
    }

    private static CollectionResponse toDto(Row row) {
        return new CollectionResponse(row.id(), row.name(), row.posts(), row.createdAt());
    }

    private static NotFoundException notFound() {
        return new NotFoundException(COLLECTION_NOT_FOUND, "Collection not found.");
    }

    private static ConflictException exists() {
        return new ConflictException(COLLECTION_EXISTS, "You already have a collection with that name.");
    }
}
