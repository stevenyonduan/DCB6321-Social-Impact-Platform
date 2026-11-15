package edu.gatech.sip6321.organization;

import java.util.UUID;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping(path = "/api/organizations", produces = "application/json")
@ConditionalOnProperty(name = "SIP_AUTH_ENABLED", havingValue = "true")
public class OrganizationController {
    private final OrganizationService organizations;

    public OrganizationController(OrganizationService organizations) {
        this.organizations = organizations;
    }

    @GetMapping("/me")
    public ResponseEntity<OrganizationResponse> me(@RequestHeader HttpHeaders headers) {
        return response(organizations.me(headers.get(HttpHeaders.AUTHORIZATION)));
    }

    @GetMapping("/{id}")
    public ResponseEntity<OrganizationResponse> get(
            @RequestHeader HttpHeaders headers, @PathVariable UUID id) {
        return response(organizations.get(headers.get(HttpHeaders.AUTHORIZATION), id));
    }

    @PatchMapping(path = "/{id}", consumes = "application/json")
    public ResponseEntity<OrganizationResponse> patch(
            @RequestHeader HttpHeaders headers,
            @PathVariable UUID id,
            @RequestBody OrganizationPatch patch) {
        return response(organizations.patch(headers.get(HttpHeaders.AUTHORIZATION), id, patch));
    }

    private ResponseEntity<OrganizationResponse> response(OrganizationResponse profile) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(profile);
    }
}
