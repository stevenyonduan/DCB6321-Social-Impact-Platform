package edu.gatech.sip6321.registration;

import jakarta.persistence.*;
import java.time.Instant;
import java.util.UUID;

/** Backend-only row. Inserts use PostgreSQL defaults and the unique event/user constraint. */
@Entity
@Table(name = "event_registrations", schema = "public")
public class EventRegistration {
    @Id private UUID id;

    @Column(name = "event_id", nullable = false, updatable = false)
    private UUID eventId;

    @Column(name = "user_id", nullable = false, updatable = false)
    private UUID userId;

    @Column(
            name = "registered_at",
            nullable = false,
            updatable = false,
            columnDefinition = "timestamptz")
    private Instant registeredAt;

    protected EventRegistration() {}

    public EventRegistrationResponse response() {
        return new EventRegistrationResponse(id, eventId, userId, registeredAt);
    }
}
