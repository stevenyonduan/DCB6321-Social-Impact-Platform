package edu.gatech.sip6321.social;

import edu.gatech.sip6321.organization.OrganizationAuthorization;
import java.time.Clock;
import java.util.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.*;
import org.springframework.web.multipart.MultipartFile;

@Service
@ConditionalOnProperty(name = "SIP_AUTH_ENABLED", havingValue = "true")
@Transactional(readOnly = true)
public class SocialService {
    private static final Logger LOG = LoggerFactory.getLogger(SocialService.class);
    private final OrganizationAuthorization auth;
    private final PostRepository posts;
    private final CommentRepository comments;
    private final InteractionRepository interactions;
    private final PostImageStorage storage;
    private final Clock clock;

    public SocialService(
            OrganizationAuthorization auth,
            PostRepository posts,
            CommentRepository comments,
            InteractionRepository interactions,
            PostImageStorage storage,
            Clock clock) {
        this.auth = auth;
        this.posts = posts;
        this.comments = comments;
        this.interactions = interactions;
        this.storage = storage;
        this.clock = clock;
    }

    private UUID account(List<String> headers) {
        return auth.account(headers).id();
    }

    private PageRequest page(int page, int size) {
        if (page < 0 || page > 10000 || size < 1 || size > 100) throw SocialFailure.invalid();
        return PageRequest.of(page, size);
    }

    private Post lock(UUID id) {
        return posts.lock(id).orElseThrow(SocialFailure::missing);
    }

    private Post owned(UUID id, UUID user) {
        var p = lock(id);
        p.own(user);
        return p;
    }

    private PostResponse view(UUID user, UUID id) {
        return posts.view(user, id).orElseThrow(SocialFailure::missing);
    }

    public PostResponse get(List<String> headers, UUID id) {
        return view(account(headers), id);
    }

    public SocialPage<PostResponse> feed(List<String> headers, int page, int size, boolean saved) {
        var user = account(headers);
        var pageable = page(page, size);
        var result = saved ? posts.saved(user, pageable) : posts.feed(user, pageable);
        return new SocialPage<>(result.getContent(), page, size, result.hasNext());
    }

    @Transactional
    public PostResponse create(List<String> headers, String text, MultipartFile file) {
        var user = account(headers);
        SocialText.validate(text);
        if (file == null && !SocialText.meaningful(text)) throw SocialFailure.invalid();
        String image = file == null ? null : upload(file);
        var p = posts.saveAndFlush(Post.create(user, text, image));
        return view(user, p.id());
    }

    @Transactional
    public PostResponse patch(List<String> headers, UUID id, boolean supplied, String text) {
        var user = account(headers);
        var p = owned(id, user);
        if (supplied) p.text(text, clock.instant());
        posts.flush();
        return view(user, id);
    }

    @Transactional
    public void delete(List<String> headers, UUID id) {
        var user = account(headers);
        var p = owned(id, user);
        cleanupAfterCommit(p.image());
        posts.delete(p);
        posts.flush();
    }

    @Transactional
    public PostResponse image(List<String> headers, UUID id, MultipartFile file, boolean remove) {
        var user = account(headers);
        var p = owned(id, user);
        String old = p.image();
        p.image(remove ? null : upload(file), clock.instant());
        cleanupAfterCommit(old);
        posts.flush();
        return view(user, id);
    }

    @Transactional
    public void interaction(List<String> headers, UUID id, String type, boolean add) {
        var user = account(headers);
        if (!Set.of("LIKE", "SAVE").contains(type)) throw SocialFailure.invalid();
        lock(id);
        if (add) interactions.add(user, id, type);
        else interactions.remove(user, id, type);
    }

    @Transactional
    public PostComment.CommentResponse comment(List<String> headers, UUID id, String content) {
        var user = account(headers);
        lock(id);
        return comments.saveAndFlush(PostComment.create(id, user, content)).response();
    }

    public SocialPage<PostComment.CommentResponse> comments(
            List<String> headers, UUID id, int page, int size) {
        var user = account(headers);
        var pageable = page(page, size);
        view(user, id);
        var result = comments.findByPostIdOrderByCreatedAtAscIdAsc(id, pageable);
        return new SocialPage<>(
                result.getContent().stream().map(PostComment::response).toList(),
                page,
                size,
                result.hasNext());
    }

    @Transactional
    public void deleteComment(List<String> headers, UUID post, UUID comment) {
        var user = account(headers);
        lock(post);
        var c =
                comments.findByIdAndPostId(comment, post)
                        .orElseThrow(SocialFailure::commentMissing);
        c.own(user);
        comments.delete(c);
        comments.flush();
    }

    private String upload(MultipartFile file) {
        // Register before SQL writes; afterCompletion also handles errors at transaction commit.
        if (!TransactionSynchronizationManager.isSynchronizationActive())
            throw new IllegalStateException("Image writes require a transaction");
        String reference = storage.upload(ValidatedImage.read(file));
        TransactionSynchronizationManager.registerSynchronization(
                new TransactionSynchronization() {
                    @Override
                    public void afterCompletion(int status) {
                        if (status == STATUS_ROLLED_BACK) cleanup(reference);
                        if (status == STATUS_UNKNOWN)
                            LOG.warn(
                                    "Social image transaction outcome unknown; reconciliation required");
                    }
                });
        return reference;
    }

    private void cleanupAfterCommit(String reference) {
        if (reference == null) return;
        TransactionSynchronizationManager.registerSynchronization(
                new TransactionSynchronization() {
                    @Override
                    public void afterCommit() {
                        cleanup(reference);
                    }
                });
    }

    private void cleanup(String reference) {
        try {
            storage.delete(reference);
        } catch (RuntimeException ignored) {
            LOG.warn("Social image cleanup failed; orphan reconciliation required");
        }
    }
}
