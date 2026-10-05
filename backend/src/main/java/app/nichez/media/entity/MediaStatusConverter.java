package app.nichez.media.entity;

import app.nichez.common.DbEnumConverter;
import jakarta.persistence.Converter;

@Converter
public class MediaStatusConverter extends DbEnumConverter<MediaStatus> {

    public MediaStatusConverter() {
        super(MediaStatus.class);
    }
}
