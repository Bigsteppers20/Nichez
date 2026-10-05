package app.nichez.common;

import jakarta.persistence.AttributeConverter;

/**
 * Maps a {@link DbValue} enum to and from its text column. A subclass is three lines:
 *
 * <pre>{@code
 * @Converter
 * public class PostStatusConverter extends DbEnumConverter<PostStatus> {
 *     public PostStatusConverter() { super(PostStatus.class); }
 * }
 * }</pre>
 *
 * Use it on the field with {@code @Convert(converter = PostStatusConverter.class)}.
 * A value the enum does not know is an error, not a silent null: it means the database and
 * the code have drifted apart.
 */
public abstract class DbEnumConverter<E extends Enum<E> & DbValue> implements AttributeConverter<E, String> {

    private final Class<E> type;

    protected DbEnumConverter(Class<E> type) {
        this.type = type;
    }

    @Override
    public String convertToDatabaseColumn(E attribute) {
        return attribute == null ? null : attribute.dbValue();
    }

    @Override
    public E convertToEntityAttribute(String dbData) {
        if (dbData == null) {
            return null;
        }
        for (E constant : type.getEnumConstants()) {
            if (constant.dbValue().equals(dbData)) {
                return constant;
            }
        }
        throw new IllegalArgumentException("Unknown " + type.getSimpleName() + " value in the database: " + dbData);
    }
}
