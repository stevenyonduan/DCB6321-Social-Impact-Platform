package edu.gatech.sip6321.registration;

import java.time.Instant;
import java.util.UUID;

public record EventRegistrationResponse(UUID id, UUID eventId, UUID userId, Instant registeredAt) {}
