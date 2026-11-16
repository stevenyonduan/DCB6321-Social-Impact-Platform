package edu.gatech.sip6321.registration;

import java.util.Optional;
import java.util.UUID;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Slice;
import org.springframework.data.jpa.repository.*;
import org.springframework.data.repository.query.Param;

public interface EventRegistrationRepository extends JpaRepository<EventRegistration, UUID> {
    // A targeted conflict clause distinguishes duplicates from all other database failures.
    // PostgreSQL waits for a competing transaction and returns 0 only for the unique pair.
    @Modifying
    @Query(
            value =
                    "INSERT INTO public.event_registrations(event_id,user_id) VALUES (:eventId,:userId) ON CONFLICT ON CONSTRAINT event_registrations_event_user_key DO NOTHING",
            nativeQuery = true)
    int insertIfAbsent(@Param("eventId") UUID eventId, @Param("userId") UUID userId);

    Optional<EventRegistration> findByEventIdAndUserId(UUID eventId, UUID userId);

    // One SQL statement: concurrent cancellations cannot both report a deleted row.
    @Modifying
    @Query("delete from EventRegistration r where r.eventId = :eventId and r.userId = :userId")
    int cancel(@Param("eventId") UUID eventId, @Param("userId") UUID userId);

    @Query(
            """
        select new edu.gatech.sip6321.registration.MyRegistrationResponse(
            r.id, e.id, e.title, e.organizationId, e.eventStart, e.eventEnd, e.location, r.registeredAt)
        from EventRegistration r join Event e on e.id = r.eventId
        where r.userId = :userId order by r.registeredAt asc, r.id asc
        """)
    Slice<MyRegistrationResponse> mine(@Param("userId") UUID userId, Pageable pageable);

    @Query(
            """
        select new edu.gatech.sip6321.registration.AttendeeResponse(
            r.id, u.id, u.firstName, u.lastName, r.registeredAt)
        from EventRegistration r join User u on u.id = r.userId
        where r.eventId = :eventId order by r.registeredAt asc, r.id asc
        """)
    Slice<AttendeeResponse> attendees(@Param("eventId") UUID eventId, Pageable pageable);
}
