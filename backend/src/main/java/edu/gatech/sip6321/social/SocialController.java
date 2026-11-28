package edu.gatech.sip6321.social;

import jakarta.servlet.http.HttpServletRequest;
import java.util.*;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.*;
import tools.jackson.databind.JsonNode;

@RestController
@ConditionalOnProperty(name = "SIP_AUTH_ENABLED", havingValue = "true")
@RequestMapping(produces = "application/json")
public class SocialController {
    private final SocialService social;

    public SocialController(SocialService social) {
        this.social = social;
    }

    private List<String> auth(HttpHeaders headers) {
        return headers.get(HttpHeaders.AUTHORIZATION);
    }

    private <T> ResponseEntity<T> ok(T body) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(body);
    }

    private ResponseEntity<Void> empty() {
        return ResponseEntity.noContent().cacheControl(CacheControl.noStore()).build();
    }

    @PostMapping(path = "/api/posts", consumes = "application/json")
    public ResponseEntity<PostResponse> create(
            @RequestHeader HttpHeaders headers, @RequestBody JsonNode body) {
        return ResponseEntity.status(201)
                .cacheControl(CacheControl.noStore())
                .body(social.create(auth(headers), SocialBody.text(body, "textContent"), null));
    }

    @PostMapping(path = "/api/posts", consumes = "multipart/form-data")
    public ResponseEntity<PostResponse> createImage(
            @RequestHeader HttpHeaders headers, MultipartHttpServletRequest request)
            throws Exception {
        var image = ImageMultipart.read(request, true);
        return ResponseEntity.status(201)
                .cacheControl(CacheControl.noStore())
                .body(social.create(auth(headers), request.getParameter("textContent"), image));
    }

    @GetMapping("/api/posts")
    public ResponseEntity<SocialPage<PostResponse>> feed(
            @RequestHeader HttpHeaders headers,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        return ok(social.feed(auth(headers), page, size, false));
    }

    @GetMapping("/api/posts/{postId}")
    public ResponseEntity<PostResponse> get(
            @RequestHeader HttpHeaders headers, @PathVariable UUID postId) {
        return ok(social.get(auth(headers), postId));
    }

    @PatchMapping(path = "/api/posts/{postId}", consumes = "application/json")
    public ResponseEntity<PostResponse> patch(
            @RequestHeader HttpHeaders headers,
            @PathVariable UUID postId,
            @RequestBody JsonNode body) {
        var text = SocialBody.text(body, "textContent");
        return ok(social.patch(auth(headers), postId, body.has("textContent"), text));
    }

    @DeleteMapping("/api/posts/{postId}")
    public ResponseEntity<Void> delete(
            @RequestHeader HttpHeaders headers, @PathVariable UUID postId) {
        social.delete(auth(headers), postId);
        return empty();
    }

    @PutMapping(path = "/api/posts/{postId}/image", consumes = "multipart/form-data")
    public ResponseEntity<PostResponse> image(
            @RequestHeader HttpHeaders headers,
            @PathVariable UUID postId,
            MultipartHttpServletRequest request)
            throws Exception {
        return ok(social.image(auth(headers), postId, ImageMultipart.read(request, false), false));
    }

    @DeleteMapping("/api/posts/{postId}/image")
    public ResponseEntity<PostResponse> removeImage(
            @RequestHeader HttpHeaders headers, @PathVariable UUID postId) {
        return ok(social.image(auth(headers), postId, null, true));
    }

    @RequestMapping(
            path = "/api/posts/{postId}/{interaction:like|save}",
            method = {RequestMethod.PUT, RequestMethod.DELETE})
    public ResponseEntity<Void> interaction(
            @RequestHeader HttpHeaders headers,
            @PathVariable UUID postId,
            @PathVariable String interaction,
            HttpServletRequest request) {
        social.interaction(
                auth(headers),
                postId,
                interaction.toUpperCase(Locale.ROOT),
                request.getMethod().equals("PUT"));
        return empty();
    }

    @GetMapping("/api/users/me/saved-posts")
    public ResponseEntity<SocialPage<PostResponse>> saved(
            @RequestHeader HttpHeaders headers,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        return ok(social.feed(auth(headers), page, size, true));
    }

    @PostMapping(path = "/api/posts/{postId}/comments", consumes = "application/json")
    public ResponseEntity<PostComment.CommentResponse> comment(
            @RequestHeader HttpHeaders headers,
            @PathVariable UUID postId,
            @RequestBody JsonNode body) {
        return ResponseEntity.status(201)
                .cacheControl(CacheControl.noStore())
                .body(social.comment(auth(headers), postId, SocialBody.text(body, "content")));
    }

    @GetMapping("/api/posts/{postId}/comments")
    public ResponseEntity<SocialPage<PostComment.CommentResponse>> comments(
            @RequestHeader HttpHeaders headers,
            @PathVariable UUID postId,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        return ok(social.comments(auth(headers), postId, page, size));
    }

    @DeleteMapping("/api/posts/{postId}/comments/{commentId}")
    public ResponseEntity<Void> deleteComment(
            @RequestHeader HttpHeaders headers,
            @PathVariable UUID postId,
            @PathVariable UUID commentId) {
        social.deleteComment(auth(headers), postId, commentId);
        return empty();
    }
}
