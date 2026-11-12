package edu.gatech.sip6321.opportunity;

import java.util.UUID;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping(path = "/api/organizations", produces = "application/json")
@ConditionalOnProperty(name = "SIP_AUTH_ENABLED", havingValue = "true")
public class OpportunityController {
    private final OpportunityService opportunities;

    public OpportunityController(OpportunityService opportunities) {
        this.opportunities = opportunities;
    }

    @PostMapping(path = "/me/opportunities", consumes = "application/json")
    public ResponseEntity<OpportunityResponse> publish(
            @RequestHeader HttpHeaders headers, @RequestBody PublishOpportunityRequest request) {
        return ResponseEntity.status(201)
                .cacheControl(CacheControl.noStore())
                .body(opportunities.publish(headers.get(HttpHeaders.AUTHORIZATION), request));
    }

    @GetMapping("/{organizationId}/opportunities")
    public ResponseEntity<OpportunityPage> list(
            @RequestHeader HttpHeaders headers,
            @PathVariable UUID organizationId,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        return ResponseEntity.ok()
                .cacheControl(CacheControl.noStore())
                .body(
                        opportunities.list(
                                headers.get(HttpHeaders.AUTHORIZATION),
                                organizationId,
                                page,
                                size));
    }
}
