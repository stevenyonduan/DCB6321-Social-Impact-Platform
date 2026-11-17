package edu.gatech.sip6321.social;

import java.time.Instant;
import java.util.UUID;

public record PostResponse(
        UUID id,
        UUID authorId,
        UUID organizationId,
        String textContent,
        String imageReference,
        Instant createdAt,
        Instant updatedAt,
        long likeCount,
        long commentCount,
        boolean liked,
        boolean saved) {}
