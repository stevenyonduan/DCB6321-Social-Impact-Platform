package edu.gatech.sip6321.account.interests;

import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import edu.gatech.sip6321.auth.ProfileFailure;
import edu.gatech.sip6321.organization.OrganizationFailure;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

@WebMvcTest(controllers = InterestController.class, properties = "SIP_AUTH_ENABLED=true")
class InterestControllerTest {
    private static final String PERSONAL = "/api/users/me/interests";
    private static final String ORGANIZATION = "/api/organizations/me/interests";
    private static final String TOKEN = "Bearer test-token";

    @Autowired MockMvc mvc;
    @MockitoBean InterestService interests;

    @Test
    void catalogRequiresSignInAndUsesExistingAuthErrorShape() throws Exception {
        when(interests.catalog(null)).thenThrow(ProfileFailure.unauthorized());
        mvc.perform(get("/api/interests"))
                .andExpect(status().isUnauthorized())
                .andExpect(header().string("WWW-Authenticate", "Bearer"))
                .andExpect(jsonPath("$.code").value("UNAUTHORIZED"));
    }

    @Test
    void organizationInterestReadRejectsAnInvalidOrganizationId() throws Exception {
        mvc.perform(get("/api/organizations/invalid/interests").header("Authorization", TOKEN))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_REQUEST"));
        verifyNoInteractions(interests);
    }

    @Test
    void anEmptySelectionClearsTheList() throws Exception {
        when(interests.replaceUserSelections(List.of(TOKEN), new InterestSelection(Set.of())))
                .thenReturn(List.of());
        mvc.perform(
                        put(PERSONAL)
                                .header("Authorization", TOKEN)
                                .contentType("application/json")
                                .content("{\"interestIds\":[]}"))
                .andExpect(status().isOk())
                .andExpect(header().string("Cache-Control", "no-store"))
                .andExpect(content().json("[]"));
        verify(interests).replaceUserSelections(List.of(TOKEN), new InterestSelection(Set.of()));
    }

    @Test
    void duplicateIdsBecomeOneSelection() throws Exception {
        var id = UUID.randomUUID();
        when(interests.replaceOrganizationSelections(
                        List.of(TOKEN), new InterestSelection(Set.of(id))))
                .thenReturn(List.of(new InterestOption(id, "Example")));
        mvc.perform(
                        put(ORGANIZATION)
                                .header("Authorization", TOKEN)
                                .contentType("application/json")
                                .content("{\"interestIds\":[\"%s\",\"%s\"]}".formatted(id, id)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].id").value(id.toString()))
                .andExpect(jsonPath("$[0].name").value("Example"));
    }

    @ParameterizedTest
    @ValueSource(
            strings = {
                "{}",
                "null",
                "{\"interestIds\":null}",
                "{\"interestIds\":[null]}",
                "{\"interestIds\":[\"bad\"]}",
                "{\"interestIds\":[15]}",
                "{\"interestIds\":{}}",
                "{\"interestIds\":[],\"organizationId\":\"injected\"}"
            })
    void malformedPayloadCannotReachTheService(String payload) throws Exception {
        mvc.perform(
                        put(PERSONAL)
                                .header("Authorization", TOKEN)
                                .contentType("application/json")
                                .content(payload))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_REQUEST"));
        verifyNoInteractions(interests);
    }

    @Test
    void nonManagerGetsForbidden() throws Exception {
        when(interests.organizationSelections(List.of(TOKEN)))
                .thenThrow(OrganizationFailure.forbidden());
        mvc.perform(get(ORGANIZATION).header("Authorization", TOKEN))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("FORBIDDEN"));
    }
}
