package edu.gatech.sip6321.auth;

import jakarta.validation.Valid;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

@RestController
@ConditionalOnProperty(name = "SIP_AUTH_ENABLED", havingValue = "true")
public class AuthenticationController {
    private final SignupService signup;

    private final LoginService login;

    public AuthenticationController(SignupService signup, LoginService login) {
        this.signup = signup;
        this.login = login;
    }

    @PostMapping(
            path = "/api/auth/login",
            consumes = "application/json",
            produces = "application/json")
    public ResponseEntity<SignupResponse> login(@Valid @RequestBody LoginRequest request) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(login.login(request));
    }

    @PostMapping(
            path = "/api/auth/signup",
            consumes = "application/json",
            produces = "application/json")
    public ResponseEntity<SignupResponse> signup(@Valid @RequestBody SignupRequest request) {
        return ResponseEntity.status(201)
                .cacheControl(CacheControl.noStore())
                .body(signup.signup(request));
    }
}
