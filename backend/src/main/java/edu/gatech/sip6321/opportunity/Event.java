package edu.gatech.sip6321.opportunity;

import jakarta.persistence.*;
import java.time.Instant;
import java.util.UUID;
import org.hibernate.annotations.Generated;
import org.hibernate.generator.EventType;

@Entity
@Table(name = "events", schema = "public")
public class Event {
    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(nullable = false, updatable = false)
    private UUID id;

    @Column(name = "organization_id", nullable = false, updatable = false)
    private UUID organizationId;

    @Column(name = "creator_id", nullable = false, updatable = false)
    private UUID creatorId;

    @Column(nullable = false, length = 255)
    private String title;

    @Column(nullable = false, columnDefinition = "text")
    private String description;

    @Column(name = "image_url", length = 255)
    private String imageUrl;

    @Column(name = "event_start", nullable = false, columnDefinition = "timestamptz")
    private Instant eventStart;

    @Column(name = "event_end", nullable = false, columnDefinition = "timestamptz")
    private Instant eventEnd;

    @Column(nullable = false, length = 255)
    private String location;

    @Enumerated(EnumType.STRING)
    @Column(name = "registration_type", nullable = false, length = 20)
    private RegistrationType registrationType;

    @Column(name = "registration_url", columnDefinition = "text")
    private String registrationUrl;

    @Generated(event = EventType.INSERT)
    @Column(
            name = "created_at",
            nullable = false,
            insertable = false,
            updatable = false,
            columnDefinition = "timestamptz")
    private Instant createdAt;

    protected Event() {}

    static Event create(UUID organizationId, UUID creatorId, PublishOpportunityRequest request) {
        var event = new Event();
        event.organizationId = organizationId;
        event.creatorId = creatorId;
        event.title = request.title();
        event.description = request.description();
        event.imageUrl = request.imageUrl();
        event.eventStart = request.eventStart();
        event.eventEnd = request.eventEnd();
        event.location = request.location();
        event.registrationType = request.registrationType();
        event.registrationUrl = request.registrationUrl();
        return event;
    }

    OpportunityResponse response() {
        return new OpportunityResponse(
                id,
                organizationId,
                creatorId,
                title,
                description,
                imageUrl,
                eventStart,
                eventEnd,
                location,
                createdAt,
                registrationType,
                registrationUrl);
    }
}
