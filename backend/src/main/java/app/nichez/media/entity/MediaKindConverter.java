package app.nichez.media.entity;

import app.nichez.common.DbEnumConverter;
import jakarta.persistence.Converter;

@Converter
public class MediaKindConverter extends DbEnumConverter<MediaKind> {

    public MediaKindConverter() {
        super(MediaKind.class);
    }
}
