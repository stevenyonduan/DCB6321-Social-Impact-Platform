package edu.gatech.sip6321.social;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import edu.gatech.sip6321.account.*;
import edu.gatech.sip6321.auth.*;
import edu.gatech.sip6321.organization.OrganizationAuthorization;
import java.time.*;
import java.util.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.data.domain.SliceImpl;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

@WebMvcTest(
        controllers = SocialController.class,
        properties = {
            "SIP_AUTH_ENABLED=true",
            "SIP_CORS_ALLOWED_ORIGINS=https://frontend.example.test"
        })
@Import({
    SocialService.class,
    OrganizationAuthorization.class,
    ProfileService.class,
    SocialExceptionHandler.class
})
class SocialControllerTest {
    @Autowired MockMvc mvc;
    @MockitoBean SupabaseAuthClient auth;
    @MockitoBean UserService users;
    @MockitoBean PostRepository posts;
    @MockitoBean CommentRepository comments;
    @MockitoBean InteractionRepository interactions;
    @MockitoBean PostImageStorage storage;
    @MockitoBean Clock clock;
    UUID id = SocialServiceTest.ID, user = SocialServiceTest.USER;
    Post p;
    String path;

    @BeforeEach
    void setup() {
        path = "/api/posts/" + id;
        when(auth.verifyAccessToken("test")).thenReturn(user);
        var account = mock(User.class);
        when(account.getId()).thenReturn(user);
        when(account.getRole()).thenReturn(UserRole.STANDARD);
        when(users.getById(user)).thenReturn(account);
        p = mock(Post.class);
        when(p.id()).thenReturn(id);
        when(posts.lock(id)).thenReturn(Optional.of(p));
        when(posts.saveAndFlush(any())).thenReturn(p);
        when(posts.view(user, id))
                .thenReturn(
                        Optional.of(
                                new PostResponse(
                                        id,
                                        user,
                                        null,
                                        "Hello",
                                        null,
                                        SocialServiceTest.NOW,
                                        SocialServiceTest.NOW,
                                        1,
                                        2,
                                        true,
                                        false)));
        when(posts.feed(eq(user), any())).thenReturn(new SliceImpl<>(List.of()));
        when(posts.saved(eq(user), any())).thenReturn(new SliceImpl<>(List.of()));
        when(clock.instant()).thenReturn(SocialServiceTest.NOW);
    }

    @Test
    void createsAndReadsWithoutPrivateAccountFields() throws Exception {
        mvc.perform(
                        post("/api/posts")
                                .header("Authorization", "Bearer test")
                                .contentType("application/json")
                                .content("{\"textContent\":\"Hello\"}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.authorId").value(user.toString()))
                .andExpect(jsonPath("$.organizationId").isEmpty())
                .andExpect(jsonPath("$.email").doesNotExist())
                .andExpect(header().string("Cache-Control", "no-store"));
        mvc.perform(get(path).header("Authorization", "Bearer test"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.likeCount").value(1))
                .andExpect(jsonPath("$.liked").value(true));
    }

    @ParameterizedTest
    @ValueSource(
            strings = {
                "authorId",
                "userId",
                "organizationId",
                "role",
                "createdAt",
                "updatedAt",
                "id",
                "imageUrl",
                "other"
            })
    void rejectsProtectedAndUnknownFieldsIncludingNull(String field) throws Exception {
        for (String route : List.of("/api/posts", path, path + "/comments")) {
            var request = route.equals(path) ? patch(route) : post(route);
            mvc.perform(
                            request.header("Authorization", "Bearer test")
                                    .contentType("application/json")
                                    .content("{\"" + field + "\":null}"))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.code").value("INVALID_REQUEST"));
        }
        verify(posts, never()).saveAndFlush(any());
    }

    @ParameterizedTest
    @ValueSource(
            strings = {
                "null",
                "[]",
                "{\"textContent\":12}",
                "{\"textContent\":false}",
                "{\"textContent\":\"  \"}",
                "{}"
            })
    void rejectsInvalidCreate(String body) throws Exception {
        mvc.perform(
                        post("/api/posts")
                                .header("Authorization", "Bearer test")
                                .contentType("application/json")
                                .content(body))
                .andExpect(status().isBadRequest());
    }

    @Test
    void everyEndpointAuthenticatesBeforeRepositoryAccess() throws Exception {
        var requests =
                List.of(
                        post("/api/posts")
                                .contentType("application/json")
                                .content("{\"textContent\":\"Hello\"}"),
                        get("/api/posts"),
                        get(path),
                        patch(path).contentType("application/json").content("{}"),
                        delete(path),
                        delete(path + "/image"),
                        put(path + "/like"),
                        delete(path + "/like"),
                        put(path + "/save"),
                        delete(path + "/save"),
                        get("/api/users/me/saved-posts"),
                        post(path + "/comments")
                                .contentType("application/json")
                                .content("{\"content\":\"Hi\"}"),
                        get(path + "/comments"),
                        delete(path + "/comments/" + UUID.randomUUID()));
        for (var request : requests)
            mvc.perform(request)
                    .andExpect(status().isUnauthorized())
                    .andExpect(header().string("WWW-Authenticate", "Bearer"))
                    .andExpect(header().string("Cache-Control", "no-store"));
        verifyNoInteractions(posts, comments, interactions, storage);
    }

    @Test
    void preservesAuthAndDatabaseErrorMappings() throws Exception {
        when(users.getById(user)).thenThrow(new AccountNotFoundException());
        mvc.perform(get(path).header("Authorization", "Bearer test"))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.code").value("ACCOUNT_PROVISIONING_REQUIRED"));
        when(auth.verifyAccessToken("test")).thenThrow(ProfileFailure.unauthorized());
        mvc.perform(get(path).header("Authorization", "Bearer test"))
                .andExpect(status().isUnauthorized());
        doThrow(ProfileFailure.database()).when(auth).verifyAccessToken("test");
        mvc.perform(get(path).header("Authorization", "Bearer test"))
                .andExpect(status().isServiceUnavailable());
    }

    @Test
    void invalidIdsPaginationMissingAndForbiddenAreSafe() throws Exception {
        for (String route :
                List.of(
                        "/api/posts/not-uuid",
                        "/api/posts?page=-1",
                        "/api/posts?page=10001",
                        "/api/posts?size=0",
                        "/api/users/me/saved-posts?size=101"))
            mvc.perform(get(route).header("Authorization", "Bearer test"))
                    .andExpect(status().isBadRequest());
        when(posts.view(user, id)).thenReturn(Optional.empty());
        mvc.perform(get(path).header("Authorization", "Bearer test"))
                .andExpect(status().isNotFound());
        doThrow(SocialFailure.forbidden()).when(p).own(user);
        mvc.perform(delete(path).header("Authorization", "Bearer test"))
                .andExpect(status().isForbidden());
    }

    @Test
    void databaseErrorsAreSanitizedAndNoStore() throws Exception {
        when(posts.feed(eq(user), any()))
                .thenThrow(new DataAccessResourceFailureException("secret SQL password"));
        var result =
                mvc.perform(get("/api/posts").header("Authorization", "Bearer test"))
                        .andExpect(status().isServiceUnavailable())
                        .andExpect(jsonPath("$.code").value("SOCIAL_UNAVAILABLE"))
                        .andReturn();
        assertThat(result.getResponse().getContentAsString())
                .doesNotContain("secret", "SQL", "password");
    }

    @Test
    void boundsChunkedJsonAndRejectsBodiesOnInteractions() throws Exception {
        mvc.perform(
                        post("/api/posts")
                                .header("Authorization", "Bearer test")
                                .contentType("application/json")
                                .content("x".repeat(16385))
                                .with(
                                        r -> {
                                            r.removeHeader("Content-Length");
                                            return r;
                                        }))
                .andExpect(status().is(413));
        mvc.perform(
                        put(path + "/like")
                                .header("Authorization", "Bearer test")
                                .contentType("application/json")
                                .content("{\"userId\":\"x\"}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void multipartRejectsUnknownDuplicateAndMissingFields() throws Exception {
        for (String field : List.of("authorId", "organizationId", "imageUrl", "path"))
            mvc.perform(
                            multipart("/api/posts")
                                    .file(ValidatedImageTest.file("png"))
                                    .param(field, "x")
                                    .header("Authorization", "Bearer test"))
                    .andExpect(status().isBadRequest());
        mvc.perform(
                        multipart("/api/posts")
                                .file(ValidatedImageTest.file("png"))
                                .file(ValidatedImageTest.file("png"))
                                .header("Authorization", "Bearer test"))
                .andExpect(status().isBadRequest());
        mvc.perform(
                        multipart("/api/posts")
                                .param("textContent", "Hello")
                                .header("Authorization", "Bearer test"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void imageEndpointsAuthenticateEvenWithoutValidStorage() throws Exception {
        mvc.perform(multipart("/api/posts").file(ValidatedImageTest.file("png")))
                .andExpect(status().isUnauthorized());
        mvc.perform(
                        multipart(path + "/image")
                                .file(ValidatedImageTest.file("png"))
                                .with(
                                        r -> {
                                            r.setMethod("PUT");
                                            return r;
                                        }))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void corsSupportsImageAndMutationMethods() throws Exception {
        mvc.perform(
                        options(path + "/image")
                                .header("Origin", "https://frontend.example.test")
                                .header("Access-Control-Request-Method", "PUT")
                                .header(
                                        "Access-Control-Request-Headers",
                                        "Authorization,Content-Type"))
                .andExpect(status().isOk())
                .andExpect(
                        header().string(
                                        "Access-Control-Allow-Origin",
                                        "https://frontend.example.test"));
    }
}
