package edu.gatech.sip6321.organization;

import jakarta.persistence.*;
import java.time.Instant;
import java.util.UUID;
import org.hibernate.annotations.Generated;
import org.hibernate.generator.EventType;

@Entity
@Table(name = "organizations", schema = "public")
public class Organization {
    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(nullable = false, updatable = false)
    private UUID id;

    @Column(nullable = false, length = 255)
    private String name;

    @Column(columnDefinition = "text")
    private String description;

    @Column(length = 255)
    private String website;

    @Generated(event = EventType.INSERT)
    @Column(name = "is_verified", nullable = false, insertable = false, updatable = false)
    private boolean verified;

    @Generated(event = EventType.INSERT)
    @Column(
            name = "created_at",
            nullable = false,
            insertable = false,
            updatable = false,
            columnDefinition = "timestamptz")
    private Instant createdAt;

    protected Organization() {}

    static Organization create(String name, String description, String website) {
        OrganizationPatch.validate("name", name);
        OrganizationPatch.validate("description", description);
        OrganizationPatch.validate("website", website);
        var organization = new Organization();
        organization.name = name;
        organization.description = description;
        organization.website = website;
        return organization;
    }

    void apply(OrganizationPatch patch) {
        if (patch.contains("name")) name = patch.value("name");
        if (patch.contains("description")) description = patch.value("description");
        if (patch.contains("website")) website = patch.value("website");
    }

    public UUID getId() {
        return id;
    }

    public String getName() {
        return name;
    }

    public String getDescription() {
        return description;
    }

    public String getWebsite() {
        return website;
    }

    public boolean isVerified() {
        return verified;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }
}
