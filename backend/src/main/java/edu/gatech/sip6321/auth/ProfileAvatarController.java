package edu.gatech.sip6321.auth;

import edu.gatech.sip6321.account.ProfileAvatarService;
import edu.gatech.sip6321.social.ImageMultipart;
import jakarta.servlet.ServletException;
import java.io.IOException;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartHttpServletRequest;

@RestController
@RequestMapping("/api/users/me/avatar")
@ConditionalOnProperty(name = "SIP_AUTH_ENABLED", havingValue = "true")
public class ProfileAvatarController {
    private final ProfileAvatarService avatars;

    public ProfileAvatarController(ProfileAvatarService avatars) {
        this.avatars = avatars;
    }

    @GetMapping
    public ResponseEntity<byte[]> get(@RequestHeader HttpHeaders headers) {
        var photo = avatars.get(headers.get(HttpHeaders.AUTHORIZATION));
        return ResponseEntity.ok()
                .cacheControl(CacheControl.noStore())
                .contentType(MediaType.parseMediaType(photo.mediaType()))
                .header("X-Content-Type-Options", "nosniff")
                .body(photo.bytes());
    }

    @PutMapping(
            consumes = MediaType.MULTIPART_FORM_DATA_VALUE,
            produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<ProfileResponse> replace(
            @RequestHeader HttpHeaders headers, MultipartHttpServletRequest request)
            throws IOException, ServletException {
        return ResponseEntity.ok()
                .cacheControl(CacheControl.noStore())
                .body(
                        avatars.replace(
                                headers.get(HttpHeaders.AUTHORIZATION),
                                ImageMultipart.read(request, false)));
    }

    @DeleteMapping(produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<ProfileResponse> remove(@RequestHeader HttpHeaders headers) {
        return ResponseEntity.ok()
                .cacheControl(CacheControl.noStore())
                .body(avatars.remove(headers.get(HttpHeaders.AUTHORIZATION)));
    }
}
