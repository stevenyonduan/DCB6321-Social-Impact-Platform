package edu.gatech.sip6321;

import static org.assertj.core.api.Assertions.assertThat;

import com.zaxxer.hikari.HikariDataSource;
import edu.gatech.sip6321.account.UserRepository;
import edu.gatech.sip6321.account.UserService;
import jakarta.persistence.EntityManagerFactory;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyStore;
import java.util.Base64;
import javax.sql.DataSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.test.context.ConfigDataApplicationContextInitializer;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

class DeploymentConfigurationTest {
    private static final String ENCRYPTED_URL =
            "jdbc:postgresql://database.invalid:5432/postgres?sslmode=require";
    @TempDir Path temporary;
    private String verifiedUrl;
    private final ApplicationContextRunner runner =
            new ApplicationContextRunner()
                    .withInitializer(new ConfigDataApplicationContextInitializer())
                    .withUserConfiguration(SipApplication.class);

    @BeforeEach
    void preparePublicTestCertificate() throws Exception {
        // Export a public CA from the JDK trust store. No private key or live connection is used.
        var store =
                KeyStore.getInstance(
                        Path.of(System.getProperty("java.home"), "lib", "security", "cacerts")
                                .toFile(),
                        "changeit".toCharArray());
        var certificate = store.getCertificate(store.aliases().nextElement());
        Path ca = temporary.resolve("test-ca.crt");
        Files.writeString(
                ca,
                "-----BEGIN CERTIFICATE-----\n"
                        + Base64.getMimeEncoder(64, new byte[] {'\n'})
                                .encodeToString(certificate.getEncoded())
                        + "\n-----END CERTIFICATE-----\n");
        verifiedUrl =
                "jdbc:postgresql://database.invalid:5432/postgres?sslmode=verify-full&sslrootcert="
                        + ca;
    }

    private ApplicationContextRunner production(String profile, String url) {
        return runner.withPropertyValues(
                "spring.profiles.active=" + profile,
                "SPRING_DATASOURCE_URL=" + url,
                "SPRING_DATASOURCE_USERNAME=test-user",
                "SPRING_DATASOURCE_PASSWORD=test-password");
    }

    @Test
    void localDefaultsRemainOffline() {
        runner.withPropertyValues("spring.profiles.active=local")
                .run(
                        context -> {
                            assertThat(context).hasNotFailed().doesNotHaveBean(DataSource.class);
                            assertThat(context)
                                    .doesNotHaveBean(EntityManagerFactory.class)
                                    .doesNotHaveBean(UserRepository.class)
                                    .doesNotHaveBean(UserService.class);
                            assertThat(context.getEnvironment().getProperty("server.address"))
                                    .isEqualTo("127.0.0.1");
                            assertThat(context.getEnvironment().getProperty("server.port"))
                                    .isEqualTo("8080");
                        });
    }

    @ParameterizedTest
    @ValueSource(strings = {"prod", "render"})
    void productionUsesStandardVariablesAndRequiredTlsWithoutCertificateOrConnection(
            String profile) {
        production(profile, ENCRYPTED_URL)
                .withPropertyValues("PORT=10000", "SERVER_PORT=9090")
                .run(
                        context -> {
                            assertThat(context).hasNotFailed().hasSingleBean(DataSource.class);
                            var environment = context.getEnvironment();
                            assertThat(environment.getProperty("server.address"))
                                    .isEqualTo("0.0.0.0");
                            assertThat(environment.getProperty("server.port")).isEqualTo("10000");
                            assertThat(environment.getProperty("spring.sql.init.mode"))
                                    .isEqualTo("never");
                            assertThat(environment.getProperty("spring.jpa.hibernate.ddl-auto"))
                                    .isEqualTo("none");
                            assertThat(environment.getProperty("spring.jpa.open-in-view"))
                                    .isEqualTo("false");
                            assertThat(context)
                                    .hasSingleBean(UserRepository.class)
                                    .hasSingleBean(UserService.class);
                            var datasource = context.getBean(HikariDataSource.class);
                            assertThat(datasource.getJdbcUrl()).isEqualTo(ENCRYPTED_URL);
                            assertThat(datasource.getUsername()).isEqualTo("test-user");
                            assertThat(datasource.getPassword()).isEqualTo("test-password");
                            assertThat(datasource.getMaximumPoolSize()).isEqualTo(3);
                            assertThat(datasource.getMinimumIdle()).isZero();
                            assertThat(datasource.getHikariPoolMXBean()).isNull();
                        });
    }

    @ParameterizedTest
    @ValueSource(strings = {"disable", "allow", "prefer", "verify-ca", ""})
    void rejectsTlsDowngrades(String mode) {
        production("prod", verifiedUrl.replace("verify-full", mode))
                .run(context -> assertThat(context).hasFailed());
    }

    @ParameterizedTest
    @ValueSource(
            strings = {
                "&sslfactory=org.postgresql.ssl.NonValidatingFactory",
                "&sslhostnameverifier=unsafe.Verifier",
                "&sslmode=require",
                "&%73slmode=require",
                "&password=do-not-log-this",
                "&sslrootcert=/missing.crt"
            })
    void rejectsTlsBypassesAndDuplicateOptions(String suffix) {
        production("prod", verifiedUrl + suffix)
                .run(
                        context -> {
                            assertThat(context).hasFailed();
                            assertThat(context.getStartupFailure())
                                    .hasStackTraceContaining("Production JDBC URL");
                            assertThat(context.getStartupFailure())
                                    .hasStackTraceContaining("verify-full");
                            assertThat(context.getStartupFailure().toString())
                                    .doesNotContain("do-not-log-this");
                        });
    }

    @Test
    void rejectsMissingOrInvalidCertificate() throws Exception {
        production("prod", verifiedUrl.replace("test-ca.crt", "missing.crt"))
                .run(context -> assertThat(context).hasFailed());
        Files.writeString(temporary.resolve("test-ca.crt"), "not a certificate");
        production("prod", verifiedUrl).run(context -> assertThat(context).hasFailed());
    }

    @Test
    void retainsOptionalCertificateVerifiedTls() {
        production("prod", verifiedUrl)
                .run(context -> assertThat(context).hasNotFailed().hasSingleBean(DataSource.class));
    }

    @ParameterizedTest
    @ValueSource(
            strings = {
                "jdbc:postgresql://database.invalid:5432/postgres",
                "jdbc:postgresql://database.invalid:5432/postgres?ssl=true",
                "postgresql://database.invalid:5432/postgres?sslmode=require"
            })
    void rejectsMissingExplicitTlsModeOrNonJdbcConnections(String url) {
        production("prod", url).run(context -> assertThat(context).hasFailed());
    }

    @Test
    void requiresCredentials() {
        production("prod", verifiedUrl)
                .withPropertyValues("SPRING_DATASOURCE_PASSWORD=")
                .run(context -> assertThat(context).hasFailed());
    }

    @Test
    void rejectsMixedOfflineAndProductionProfiles() {
        production("prod,local", verifiedUrl).run(context -> assertThat(context).hasFailed());
    }
}
