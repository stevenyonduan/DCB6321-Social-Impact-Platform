package edu.gatech.sip6321.auth;

import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import edu.gatech.sip6321.account.ProfileAvatarService;
import edu.gatech.sip6321.social.SocialFailure;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.http.HttpMethod;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

@WebMvcTest(controllers = ProfileAvatarController.class, properties = "SIP_AUTH_ENABLED=true")
class ProfileAvatarControllerTest {
    static final String PATH = "/api/users/me/avatar";
    static final String TOKEN = "Bearer test-access";
    final MockMultipartFile image =
            new MockMultipartFile("image", "photo.png", "image/png", new byte[] {1, 2, 3});

    @Autowired MockMvc mvc;
    @MockitoBean ProfileAvatarService avatars;

    @Test
    void readsImageBytesWithoutCachingOrMimeSniffing() throws Exception {
        var bytes = new byte[] {1, 2, 3};
        when(avatars.get(List.of(TOKEN)))
                .thenReturn(new ProfileAvatarService.Photo(bytes, "image/png"));
        mvc.perform(get(PATH).header("Authorization", TOKEN))
                .andExpect(status().isOk())
                .andExpect(content().contentType("image/png"))
                .andExpect(content().bytes(bytes))
                .andExpect(header().string("Cache-Control", "no-store"))
                .andExpect(header().string("X-Content-Type-Options", "nosniff"));
    }

    @Test
    void returnsStructuredMissingPhotoEvenForAnImageAcceptHeader() throws Exception {
        when(avatars.get(List.of(TOKEN))).thenThrow(ProfileFailure.avatarMissing());
        mvc.perform(get(PATH).header("Authorization", TOKEN).accept("image/png"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("AVATAR_NOT_FOUND"));
    }

    @Test
    void rejectsExtraOrMissingMultipartFields() throws Exception {
        mvc.perform(multipart(HttpMethod.PUT, PATH).file(image).file(image))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_REQUEST"));
        mvc.perform(multipart(HttpMethod.PUT, PATH).file(image).param("userId", "other"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_REQUEST"));
        mvc.perform(multipart(HttpMethod.PUT, PATH).param("image", "https://example.test/avatar"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_REQUEST"));
        verifyNoInteractions(avatars);
    }

    @Test
    void rejectsNonMultipartUploadAndDeletionWithABody() throws Exception {
        mvc.perform(put(PATH).contentType("application/json").content("{}"))
                .andExpect(status().isUnsupportedMediaType())
                .andExpect(jsonPath("$.code").value("UNSUPPORTED_MEDIA_TYPE"));
        mvc.perform(delete(PATH).contentType("application/json").content("{}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_REQUEST"));
        verifyNoInteractions(avatars);
    }

    @Test
    void storageErrorsAreReturnedInTheProfileErrorFormat() throws Exception {
        when(avatars.replace(List.of(TOKEN), image)).thenThrow(SocialFailure.storage());
        mvc.perform(multipart(HttpMethod.PUT, PATH).file(image).header("Authorization", TOKEN))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.code").value("STORAGE_UNAVAILABLE"))
                .andExpect(header().string("Cache-Control", "no-store"));
    }
}
