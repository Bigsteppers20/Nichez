package app.nichez.common;

import jakarta.persistence.MappedSuperclass;
import jakarta.persistence.PostLoad;
import jakarta.persistence.PostPersist;
import jakarta.persistence.Transient;
import org.springframework.data.domain.Persistable;

/**
 * Base for entities whose id is assigned by the application (a UUID generated before the
 * insert), not by the database.
 *
 * <p>Without this, {@code repository.save(entity)} cannot tell a new entity from an existing
 * one, so it runs a SELECT first and then merges. Here a freshly constructed entity is new
 * and one loaded from the database is not, so {@code save} inserts straight away. It needs
 * no extra column, unlike a {@code @Version} field.
 */
@MappedSuperclass
public abstract class AssignedIdEntity<ID> implements Persistable<ID> {

    @Transient
    private boolean isNew = true;

    @Override
    public boolean isNew() {
        return isNew;
    }

    @PostLoad
    @PostPersist
    void markNotNew() {
        this.isNew = false;
    }
}
