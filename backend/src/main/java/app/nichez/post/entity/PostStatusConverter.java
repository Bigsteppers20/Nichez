package app.nichez.post.entity;

import app.nichez.common.DbEnumConverter;
import jakarta.persistence.Converter;

@Converter
public class PostStatusConverter extends DbEnumConverter<PostStatus> {

    public PostStatusConverter() {
        super(PostStatus.class);
    }
}
