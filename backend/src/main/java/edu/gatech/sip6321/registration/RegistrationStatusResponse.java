package edu.gatech.sip6321.registration;

import java.time.Instant;
import java.util.UUID;

public record RegistrationStatusResponse(
        UUID eventId, boolean registered, Registration registration) {
    public record Registration(UUID id, Instant registeredAt) {}
}
