package edu.gatech.sip6321.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import edu.gatech.sip6321.account.UserService;
import jakarta.validation.Validator;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

class SignupConfigurationTest {
    private final ApplicationContextRunner runner =
            new ApplicationContextRunner()
                    .withUserConfiguration(SignupConfiguration.class)
                    .withBean(ObjectMapper.class, () -> JsonMapper.builder().build())
                    .withBean(Validator.class, () -> mock(Validator.class))
                    .withBean(UserService.class, () -> mock(UserService.class));

    @Test
    void disabledByDefaultAndNeedsNoSupabaseConfiguration() {
        runner.run(
                context ->
                        assertThat(context)
                                .hasNotFailed()
                                .doesNotHaveBean(SupabaseAuthClient.class));
    }

    @Test
    void enabledConfigurationBuildsWithoutContactingAuthOrDatabase() {
        enabled()
                .run(
                        context ->
                                assertThat(context)
                                        .hasNotFailed()
                                        .hasSingleBean(SignupService.class)
                                        .hasSingleBean(LoginService.class));
    }

    @ParameterizedTest
    @ValueSource(
            strings = {
                "",
                "http://project.supabase.co",
                "https://user:pass@project.supabase.co",
                "https://project.supabase.co/path",
                "https://project.supabase.co?key=secret",
                "https://project.supabase.co#fragment"
            })
    void refusesInvalidOriginsWithoutPrintingValues(String url) {
        enabled()
                .withPropertyValues("SUPABASE_URL=" + url)
                .run(
                        context -> {
                            assertThat(context).hasFailed();
                            assertThat(context.getStartupFailure())
                                    .hasRootCauseMessage(
                                            "SUPABASE_URL must be an HTTPS project origin");
                        });
    }

    @ParameterizedTest
    @ValueSource(
            strings = {
                "",
                "sb_secret_never-use",
                "legacy-service-role",
                "sb_publishable_bad\r\nvalue"
            })
    void refusesMissingOrPrivilegedKeys(String key) {
        enabled()
                .withPropertyValues("SUPABASE_PUBLISHABLE_KEY=" + key)
                .run(
                        context -> {
                            assertThat(context).hasFailed();
                            assertThat(context.getStartupFailure())
                                    .hasRootCauseMessage(
                                            "SUPABASE_PUBLISHABLE_KEY must be a modern publishable key");
                        });
    }

    @ParameterizedTest
    @ValueSource(strings = {"0", "-1", "30001", "invalid"})
    void timeoutsMustBeFiniteAndBounded(String timeout) {
        for (String name :
                new String[] {"SIP_AUTH_CONNECT_TIMEOUT_MS", "SIP_AUTH_REQUEST_TIMEOUT_MS"}) {
            enabled()
                    .withPropertyValues(name + "=" + timeout)
                    .run(context -> assertThat(context).hasFailed());
        }
    }

    private ApplicationContextRunner enabled() {
        return runner.withPropertyValues(
                "spring.profiles.active=database",
                "SIP_AUTH_ENABLED=true",
                "SUPABASE_URL=https://project.supabase.co",
                "SUPABASE_PUBLISHABLE_KEY=sb_publishable_test");
    }
}
