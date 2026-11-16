package edu.gatech.sip6321.registration;

import java.time.Instant;
import java.util.UUID;

public record MyRegistrationResponse(
        UUID id,
        UUID eventId,
        String title,
        UUID organizationId,
        Instant eventStart,
        Instant eventEnd,
        String location,
        Instant registeredAt) {}
