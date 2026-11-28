package edu.gatech.sip6321.account.interests;

import java.util.List;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping(produces = "application/json")
@ConditionalOnProperty(name = "SIP_AUTH_ENABLED", havingValue = "true")
class InterestController {
    private final InterestService interests;

    InterestController(InterestService interests) {
        this.interests = interests;
    }

    @GetMapping("/api/interests")
    ResponseEntity<List<InterestOption>> catalog(@RequestHeader HttpHeaders headers) {
        return response(interests.catalog(headers.get(HttpHeaders.AUTHORIZATION)));
    }

    @GetMapping("/api/users/me/interests")
    ResponseEntity<List<InterestOption>> userSelections(@RequestHeader HttpHeaders headers) {
        return response(interests.userSelections(headers.get(HttpHeaders.AUTHORIZATION)));
    }

    @PutMapping(path = "/api/users/me/interests", consumes = "application/json")
    ResponseEntity<List<InterestOption>> replaceUserSelections(
            @RequestHeader HttpHeaders headers, @RequestBody InterestSelection selection) {
        return response(
                interests.replaceUserSelections(headers.get(HttpHeaders.AUTHORIZATION), selection));
    }

    @GetMapping("/api/organizations/me/interests")
    ResponseEntity<List<InterestOption>> organizationSelections(
            @RequestHeader HttpHeaders headers) {
        return response(interests.organizationSelections(headers.get(HttpHeaders.AUTHORIZATION)));
    }

    @PutMapping(path = "/api/organizations/me/interests", consumes = "application/json")
    ResponseEntity<List<InterestOption>> replaceOrganizationSelections(
            @RequestHeader HttpHeaders headers, @RequestBody InterestSelection selection) {
        return response(
                interests.replaceOrganizationSelections(
                        headers.get(HttpHeaders.AUTHORIZATION), selection));
    }

    private ResponseEntity<List<InterestOption>> response(List<InterestOption> options) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(options);
    }
}
