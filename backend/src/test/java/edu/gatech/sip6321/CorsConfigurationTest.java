package edu.gatech.sip6321;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import edu.gatech.sip6321.config.CorsConfiguration;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class CorsConfigurationTest {
    @Test
    void acceptsEmptyOrExactOrigins() {
        assertThatCode(() -> new CorsConfiguration("")).doesNotThrowAnyException();
        assertThatCode(() -> new CorsConfiguration("https://sip.example,http://localhost:5173"))
                .doesNotThrowAnyException();
    }

    @ParameterizedTest
    @ValueSource(
            strings = {
                "*",
                "https://*.vercel.app",
                "https://sip.example/",
                "null",
                "https://user:password@sip.example",
                "https://sip.example?query=1",
                "https://sip.example#fragment",
                "https://sip.example,"
            })
    void rejectsNonOriginsAndWildcards(String origin) {
        assertThatThrownBy(() -> new CorsConfiguration(origin))
                .isInstanceOf(IllegalStateException.class);
    }
}
