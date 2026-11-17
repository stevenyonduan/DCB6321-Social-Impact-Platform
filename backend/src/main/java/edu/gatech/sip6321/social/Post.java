package edu.gatech.sip6321.social;

import jakarta.persistence.*;
import java.time.Instant;
import java.util.UUID;
import org.hibernate.annotations.Generated;
import org.hibernate.generator.EventType;

@Entity
@Table(name = "posts", schema = "public")
public class Post {
    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "author_id", nullable = false, updatable = false)
    private UUID authorId;

    @Column(name = "organization_id", updatable = false)
    private UUID organizationId;

    @Column(name = "text_content", columnDefinition = "text")
    private String textContent;

    @Column(name = "image_url", length = 255)
    private String imageReference;

    @Generated(event = EventType.INSERT)
    @Column(
            name = "created_at",
            insertable = false,
            updatable = false,
            nullable = false,
            columnDefinition = "timestamptz")
    private Instant createdAt;

    @Generated(event = EventType.INSERT)
    @Column(
            name = "updated_at",
            insertable = false,
            nullable = false,
            columnDefinition = "timestamptz")
    private Instant updatedAt;

    protected Post() {}

    static Post create(UUID author, String text, String image) {
        SocialText.content(text, image);
        var p = new Post();
        p.authorId = author;
        p.textContent = text;
        p.imageReference = image;
        return p;
    }

    UUID id() {
        return id;
    }

    String image() {
        return imageReference;
    }

    void own(UUID user) {
        if (!authorId.equals(user)) throw SocialFailure.forbidden();
    }

    void text(String text, Instant now) {
        SocialText.content(text, imageReference);
        textContent = text;
        touch(now);
    }

    void image(String image, Instant now) {
        SocialText.content(textContent, image);
        imageReference = image;
        touch(now);
    }

    private void touch(Instant now) {
        now = now.truncatedTo(java.time.temporal.ChronoUnit.MICROS);
        updatedAt = now.isAfter(updatedAt) ? now : updatedAt.plusNanos(1000);
    }
}
