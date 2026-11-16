package edu.gatech.sip6321.registration;

import java.time.Instant;
import java.util.UUID;

public record AttendeeResponse(
        UUID id, UUID userId, String firstName, String lastName, Instant registeredAt) {}
