package app.nichez.post.entity;

import app.nichez.common.DbEnumConverter;
import jakarta.persistence.Converter;

@Converter
public class AudienceConverter extends DbEnumConverter<Audience> {

    public AudienceConverter() {
        super(Audience.class);
    }
}
