package edu.gatech.sip6321.account;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.PostLoad;
import jakarta.persistence.PostPersist;
import jakarta.persistence.Table;
import jakarta.persistence.Transient;
import java.time.Instant;
import java.util.UUID;
import org.hibernate.annotations.Generated;
import org.hibernate.generator.EventType;
import org.springframework.data.domain.Persistable;

/** Application profile only; authentication and passwords belong to Supabase Auth. */
@Entity
@Table(name = "users", schema = "public")
public class User implements Persistable<UUID> {
    @Id
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    @Column(name = "email", nullable = false, unique = true, length = 255)
    private String email;

    @Enumerated(EnumType.STRING)
    @Column(name = "role", nullable = false, length = 20)
    private UserRole role;

    @Column(name = "first_name", nullable = false, length = 100)
    private String firstName;

    @Column(name = "last_name", nullable = false, length = 100)
    private String lastName;

    @Column(name = "bio", columnDefinition = "text")
    private String bio;

    @Column(name = "avatar_url", length = 255)
    private String avatarUrl;

    @Column(name = "organization_id")
    private UUID organizationId;

    @Generated(event = EventType.INSERT)
    @Column(
            name = "created_at",
            nullable = false,
            insertable = false,
            updatable = false,
            columnDefinition = "timestamptz")
    private Instant createdAt;

    // Assigned Auth UUIDs must use persist on creation, never merge over an existing account.
    @Transient private boolean newRecord = true;

    protected User() {}

    static User standardAccount(UUID authUserId, String email, String firstName, String lastName) {
        var user = new User();
        user.id = authUserId;
        user.email = email;
        user.role = UserRole.STANDARD;
        user.firstName = firstName;
        user.lastName = lastName;
        return user;
    }

    @PostLoad
    @PostPersist
    private void markPersisted() {
        newRecord = false;
    }

    @Override
    public boolean isNew() {
        return newRecord;
    }

    @Override
    public UUID getId() {
        return id;
    }

    public String getEmail() {
        return email;
    }

    public UserRole getRole() {
        return role;
    }

    public String getFirstName() {
        return firstName;
    }

    public String getLastName() {
        return lastName;
    }

    public String getBio() {
        return bio;
    }

    public String getAvatarUrl() {
        return avatarUrl;
    }

    public UUID getOrganizationId() {
        return organizationId;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }
}
