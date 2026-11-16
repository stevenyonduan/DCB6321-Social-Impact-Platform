package edu.gatech.sip6321.registration;

import java.util.UUID;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping(produces = "application/json")
@ConditionalOnProperty(name = "SIP_AUTH_ENABLED", havingValue = "true")
public class EventRegistrationController {
    private final EventRegistrationService registrations;

    public EventRegistrationController(EventRegistrationService registrations) {
        this.registrations = registrations;
    }

    @PostMapping("/api/opportunities/{eventId}/registrations/me")
    public ResponseEntity<EventRegistrationResponse> register(
            @RequestHeader HttpHeaders headers,
            @PathVariable UUID eventId,
            @RequestBody(required = false) byte[] body) {
        noBody(body);
        return ResponseEntity.status(201)
                .cacheControl(CacheControl.noStore())
                .body(registrations.register(headers.get(HttpHeaders.AUTHORIZATION), eventId));
    }

    @GetMapping("/api/opportunities/{eventId}/registrations/me")
    public ResponseEntity<RegistrationStatusResponse> status(
            @RequestHeader HttpHeaders headers, @PathVariable UUID eventId) {
        return ResponseEntity.ok()
                .cacheControl(CacheControl.noStore())
                .body(registrations.status(headers.get(HttpHeaders.AUTHORIZATION), eventId));
    }

    @DeleteMapping("/api/opportunities/{eventId}/registrations/me")
    public ResponseEntity<Void> cancel(
            @RequestHeader HttpHeaders headers,
            @PathVariable UUID eventId,
            @RequestBody(required = false) byte[] body) {
        noBody(body);
        registrations.cancel(headers.get(HttpHeaders.AUTHORIZATION), eventId);
        return ResponseEntity.noContent().cacheControl(CacheControl.noStore()).build();
    }

    @GetMapping("/api/users/me/registrations")
    public ResponseEntity<RegistrationPage<MyRegistrationResponse>> mine(
            @RequestHeader HttpHeaders headers,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        return ResponseEntity.ok()
                .cacheControl(CacheControl.noStore())
                .body(registrations.mine(headers.get(HttpHeaders.AUTHORIZATION), page, size));
    }

    @GetMapping("/api/organizations/me/opportunities/{eventId}/registrations")
    public ResponseEntity<RegistrationPage<AttendeeResponse>> attendees(
            @RequestHeader HttpHeaders headers,
            @PathVariable UUID eventId,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        return ResponseEntity.ok()
                .cacheControl(CacheControl.noStore())
                .body(
                        registrations.attendees(
                                headers.get(HttpHeaders.AUTHORIZATION), eventId, page, size));
    }

    private void noBody(byte[] body) {
        if (body != null && body.length > 0) throw RegistrationFailure.invalid();
    }
}
