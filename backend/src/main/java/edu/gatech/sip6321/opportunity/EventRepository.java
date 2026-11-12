package edu.gatech.sip6321.opportunity;

import java.util.UUID;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Slice;
import org.springframework.data.jpa.repository.JpaRepository;

public interface EventRepository extends JpaRepository<Event, UUID> {
    Slice<Event> findByOrganizationIdOrderByEventStartAscIdAsc(
            UUID organizationId, Pageable pageable);
}
