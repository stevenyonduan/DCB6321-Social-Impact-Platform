package edu.gatech.sip6321.organization;

import edu.gatech.sip6321.account.OrganizationRole;
import edu.gatech.sip6321.account.UserRepository;
import java.util.List;
import java.util.UUID;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@ConditionalOnProperty(name = "SIP_AUTH_ENABLED", havingValue = "true")
@Transactional(readOnly = true)
public class OrganizationManagementService {
    private final OrganizationAuthorization authorization;
    private final OrganizationRepository organizations;
    private final UserRepository users;
    private final JdbcTemplate jdbc;
    private final ManagementAttemptLimiter limiter;

    public OrganizationManagementService(
            OrganizationAuthorization authorization,
            OrganizationRepository organizations,
            UserRepository users,
            JdbcTemplate jdbc,
            ManagementAttemptLimiter limiter) {
        this.authorization = authorization;
        this.organizations = organizations;
        this.users = users;
        this.jdbc = jdbc;
        this.limiter = limiter;
    }

    @Transactional
    public CreatedOrganization create(List<String> headers, CreateOrganizationRequest request) {
        var actor = authorization.account(headers);
        if (actor.organizationId() != null || actor.organizationRole() != null)
            throw OrganizationFailure.conflict();
        if (request == null) throw OrganizationFailure.invalid();
        var organization =
                organizations.saveAndFlush(
                        Organization.create(
                                request.name(), request.description(), request.website()));
        if (users.claimOrganization(actor.id(), organization.getId(), OrganizationRole.OWNER) != 1)
            throw OrganizationFailure.conflict();
        audit(actor.id(), actor.id(), organization.getId(), "OWNER_CREATED");
        return new CreatedOrganization(
                OrganizationResponse.of(organization), actor.id(), OrganizationRole.OWNER);
    }

    @Transactional
    public void addAdmin(List<String> headers, AddAdminRequest request) {
        var actor = authorization.owner(headers);
        requireOrganization(actor.organizationId());
        limiter.attempt(actor.id());
        if (request == null) throw OrganizationFailure.invalid();
        // Exact existing account email semantics. No email directory or cross-organization details.
        var target = users.findByEmail(request.email()).orElseThrow(OrganizationFailure::conflict);
        if (users.claimOrganization(target.getId(), actor.organizationId(), OrganizationRole.ADMIN)
                != 1) throw OrganizationFailure.conflict();
        audit(actor.id(), target.getId(), actor.organizationId(), "ADMIN_ADDED");
    }

    public List<ManagementMember> admins(List<String> headers) {
        var actor = authorization.manager(headers);
        requireOrganization(actor.organizationId());
        boolean ownerView = actor.organizationRole() == OrganizationRole.OWNER;
        return users.findByOrganizationIdOrderById(actor.organizationId()).stream()
                .map(member -> ManagementMember.of(member, ownerView))
                .toList();
    }

    @Transactional
    public void removeAdmin(List<String> headers, UUID target) {
        var actor = authorization.owner(headers);
        requireOrganization(actor.organizationId());
        if (actor.id().equals(target)) throw OrganizationFailure.forbidden();
        if (target == null || users.removeAdmin(target, actor.organizationId()) != 1)
            throw OrganizationFailure.memberMissing();
        audit(actor.id(), target, actor.organizationId(), "ADMIN_REMOVED");
    }

    private void requireOrganization(UUID id) {
        if (!organizations.existsById(id)) throw OrganizationFailure.missing();
    }

    private void audit(UUID actor, UUID target, UUID organization, String action) {
        jdbc.update(
                "INSERT INTO public.organization_management_audit(actor_id,target_id,organization_id,action) VALUES (?,?,?,?)",
                actor,
                target,
                organization,
                action);
    }
}
