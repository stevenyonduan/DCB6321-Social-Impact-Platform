package edu.gatech.sip6321.opportunity;

import java.time.Instant;
import java.util.UUID;

public record OpportunityResponse(
        UUID id,
        UUID organizationId,
        UUID creatorId,
        String title,
        String description,
        String imageUrl,
        Instant eventStart,
        Instant eventEnd,
        String location,
        Instant createdAt,
        RegistrationType registrationType,
        String registrationUrl) {}
