package edu.gatech.sip6321.social;

import jakarta.persistence.*;
import java.time.Instant;
import java.util.UUID;
import org.hibernate.annotations.Generated;
import org.hibernate.generator.EventType;

@Entity
@Table(name = "post_comments", schema = "public")
public class PostComment {
    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "post_id", nullable = false, updatable = false)
    private UUID postId;

    @Column(name = "author_id", nullable = false, updatable = false)
    private UUID authorId;

    @Column(nullable = false, columnDefinition = "text")
    private String content;

    @Generated(event = EventType.INSERT)
    @Column(
            name = "created_at",
            insertable = false,
            updatable = false,
            nullable = false,
            columnDefinition = "timestamptz")
    private Instant createdAt;

    protected PostComment() {}

    static PostComment create(UUID post, UUID author, String content) {
        SocialText.validate(content);
        if (!SocialText.meaningful(content)) throw SocialFailure.invalid();
        var c = new PostComment();
        c.postId = post;
        c.authorId = author;
        c.content = content;
        return c;
    }

    void own(UUID user) {
        if (!authorId.equals(user)) throw SocialFailure.forbidden();
    }

    public CommentResponse response() {
        return new CommentResponse(id, postId, authorId, content, createdAt);
    }

    public record CommentResponse(
            UUID id, UUID postId, UUID authorId, String content, Instant createdAt) {}
}
