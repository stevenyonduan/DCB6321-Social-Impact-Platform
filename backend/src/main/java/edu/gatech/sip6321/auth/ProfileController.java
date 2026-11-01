package edu.gatech.sip6321.auth;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;

@RestController
@ConditionalOnProperty(name = "SIP_AUTH_ENABLED", havingValue = "true")
public class ProfileController {
    private final ProfileService profiles;

    public ProfileController(ProfileService profiles) {
        this.profiles = profiles;
    }

    @GetMapping(path = "/api/users/me", produces = "application/json")
    public ResponseEntity<ProfileResponse> me(@RequestHeader HttpHeaders headers) {
        return ResponseEntity.ok()
                .cacheControl(CacheControl.noStore())
                .body(profiles.me(headers.get(HttpHeaders.AUTHORIZATION)));
    }
}
