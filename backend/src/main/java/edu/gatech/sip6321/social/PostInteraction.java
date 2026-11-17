package edu.gatech.sip6321.social;

import jakarta.persistence.*;
import java.io.Serializable;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "post_interactions", schema = "public")
@IdClass(PostInteraction.Key.class)
public class PostInteraction {
    @Id
    @Column(name = "user_id")
    private UUID userId;

    @Id
    @Column(name = "post_id")
    private UUID postId;

    @Id
    @Column(name = "interaction_type", length = 20)
    private String interactionType;

    @Column(name = "created_at", columnDefinition = "timestamptz")
    private Instant createdAt;

    protected PostInteraction() {}

    public record Key(UUID userId, UUID postId, String interactionType) implements Serializable {}
}
