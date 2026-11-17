package edu.gatech.sip6321.social;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

import edu.gatech.sip6321.account.*;
import edu.gatech.sip6321.auth.ProfileResponse;
import edu.gatech.sip6321.organization.OrganizationAuthorization;
import java.time.*;
import java.util.*;
import org.junit.jupiter.api.*;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.transaction.support.*;

class SocialServiceTest {
    static final UUID USER = UUID.randomUUID(), ID = UUID.randomUUID();
    static final Instant NOW = Instant.parse("2026-11-17T00:00:00Z");
    static final List<String> HEADERS = List.of("Bearer test");
    OrganizationAuthorization auth = mock(OrganizationAuthorization.class);
    PostRepository posts = mock(PostRepository.class);
    CommentRepository comments = mock(CommentRepository.class);
    InteractionRepository interactions = mock(InteractionRepository.class);
    PostImageStorage storage = mock(PostImageStorage.class);
    SocialService service =
            new SocialService(
                    auth, posts, comments, interactions, storage, Clock.fixed(NOW, ZoneOffset.UTC));
    Post p = mock(Post.class);
    PostResponse response =
            new PostResponse(ID, USER, null, "Hello", null, NOW, NOW, 0, 0, false, false);

    @BeforeEach
    void setup() {
        when(auth.account(HEADERS))
                .thenReturn(
                        new ProfileResponse(
                                USER,
                                "private@example.test",
                                UserRole.STANDARD,
                                "First",
                                "Last",
                                null,
                                null,
                                null,
                                null,
                                NOW));
        when(posts.lock(ID)).thenReturn(Optional.of(p));
        when(p.id()).thenReturn(ID);
        when(posts.saveAndFlush(any())).thenReturn(p);
        when(posts.view(USER, ID)).thenReturn(Optional.of(response));
        TransactionSynchronizationManager.initSynchronization();
    }

    @AfterEach
    void clear() {
        TransactionSynchronizationManager.clearSynchronization();
    }

    void complete(int status) {
        var syncs = TransactionSynchronizationManager.getSynchronizations();
        if (status == TransactionSynchronization.STATUS_COMMITTED)
            syncs.forEach(TransactionSynchronization::afterCommit);
        syncs.forEach(s -> s.afterCompletion(status));
    }

    @Test
    void textOnlyDoesNotRequireStorage() {
        assertThat(service.create(HEADERS, "Hello", null)).isEqualTo(response);
        verifyNoInteractions(storage);
        assertThatThrownBy(() -> service.create(HEADERS, " \u00a0", null))
                .isInstanceOf(SocialFailure.class);
    }

    @Test
    void uploadCompensatesOnWriteFailureIncludingCommitRollback() throws Exception {
        when(storage.upload(any())).thenReturn("bucket/posts/new.png");
        when(posts.saveAndFlush(any())).thenThrow(new DataIntegrityViolationException("private"));
        assertThatThrownBy(() -> service.create(HEADERS, null, ValidatedImageTest.file("png")))
                .isInstanceOf(DataIntegrityViolationException.class);
        verify(storage, never()).delete(any());
        complete(TransactionSynchronization.STATUS_ROLLED_BACK);
        verify(storage).delete("bucket/posts/new.png");
    }

    @Test
    void replacementRetainsOldObjectUntilCommitAndRollbackCleansOnlyNew() throws Exception {
        when(p.image()).thenReturn("old");
        when(storage.upload(any())).thenReturn("new");
        service.image(HEADERS, ID, ValidatedImageTest.file("jpg"), false);
        verify(storage, never()).delete(any());
        complete(TransactionSynchronization.STATUS_ROLLED_BACK);
        verify(storage).delete("new");
        verify(storage, never()).delete("old");
    }

    @Test
    void committedReplacementDeletesOnlyOldObject() throws Exception {
        when(p.image()).thenReturn("old");
        when(storage.upload(any())).thenReturn("new");
        service.image(HEADERS, ID, ValidatedImageTest.file("png"), false);
        complete(TransactionSynchronization.STATUS_COMMITTED);
        verify(storage).delete("old");
        verify(storage, never()).delete("new");
    }

    @Test
    void failedCleanupDoesNotUndoCommittedDeletion() {
        when(p.image()).thenReturn("old");
        doThrow(SocialFailure.storage()).when(storage).delete("old");
        service.delete(HEADERS, ID);
        assertThatCode(() -> complete(TransactionSynchronization.STATUS_COMMITTED))
                .doesNotThrowAnyException();
        verify(posts).delete(p);
    }

    @Test
    void ownershipCheckedBeforeUploadsAndChanges() throws Exception {
        doThrow(SocialFailure.forbidden()).when(p).own(USER);
        assertThatThrownBy(() -> service.image(HEADERS, ID, ValidatedImageTest.file("png"), false))
                .isInstanceOf(SocialFailure.class);
        assertThatThrownBy(() -> service.patch(HEADERS, ID, true, "new"))
                .isInstanceOf(SocialFailure.class);
        assertThatThrownBy(() -> service.delete(HEADERS, ID)).isInstanceOf(SocialFailure.class);
        verifyNoInteractions(storage);
    }

    @Test
    void failedUploadNeverCreatesPost() throws Exception {
        when(storage.upload(any())).thenThrow(SocialFailure.storage());
        assertThatThrownBy(() -> service.create(HEADERS, "Hello", ValidatedImageTest.file("png")))
                .isInstanceOf(SocialFailure.class);
        verify(posts, never()).saveAndFlush(any());
    }

    @Test
    void interactionsUseVerifiedIdentityAndDatabaseIdempotency() {
        for (String type : List.of("LIKE", "SAVE")) {
            service.interaction(HEADERS, ID, type, true);
            service.interaction(HEADERS, ID, type, false);
            verify(interactions).add(USER, ID, type);
            verify(interactions).remove(USER, ID, type);
        }
    }
}
