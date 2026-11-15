package edu.gatech.sip6321.organization;

import java.net.URI;
import java.util.List;
import java.util.UUID;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping(path = "/api/organizations", produces = "application/json")
@ConditionalOnProperty(name = "SIP_AUTH_ENABLED", havingValue = "true")
public class OrganizationManagementController {
    private final OrganizationManagementService management;

    public OrganizationManagementController(OrganizationManagementService management) {
        this.management = management;
    }

    @PostMapping(consumes = "application/json")
    public ResponseEntity<CreatedOrganization> create(
            @RequestHeader HttpHeaders headers, @RequestBody CreateOrganizationRequest request) {
        var created = management.create(headers.get(HttpHeaders.AUTHORIZATION), request);
        return ResponseEntity.created(
                        URI.create("/api/organizations/" + created.organization().id()))
                .cacheControl(CacheControl.noStore())
                .body(created);
    }

    @PostMapping(path = "/me/admins", consumes = "application/json")
    public ResponseEntity<Void> add(
            @RequestHeader HttpHeaders headers, @RequestBody AddAdminRequest request) {
        management.addAdmin(headers.get(HttpHeaders.AUTHORIZATION), request);
        return ResponseEntity.noContent().cacheControl(CacheControl.noStore()).build();
    }

    @GetMapping("/me/admins")
    public ResponseEntity<List<ManagementMember>> admins(@RequestHeader HttpHeaders headers) {
        return ResponseEntity.ok()
                .cacheControl(CacheControl.noStore())
                .body(management.admins(headers.get(HttpHeaders.AUTHORIZATION)));
    }

    @DeleteMapping("/me/admins/{userId}")
    public ResponseEntity<Void> remove(
            @RequestHeader HttpHeaders headers, @PathVariable UUID userId) {
        management.removeAdmin(headers.get(HttpHeaders.AUTHORIZATION), userId);
        return ResponseEntity.noContent().cacheControl(CacheControl.noStore()).build();
    }
}
