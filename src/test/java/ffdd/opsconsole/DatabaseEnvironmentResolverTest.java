package ffdd.opsconsole;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.HashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;

class DatabaseEnvironmentResolverTest {

    @Test
    void resolvesNexionBundleAsAuthoritative() {
        DatabaseEnvironmentResolver.ResolvedDatabase resolved = DatabaseEnvironmentResolver.resolve(Map.of(
                "NEXION_DB_URL", "jdbc:mysql://nexion-db:3307/nexion_contract",
                "NEXION_DB_USERNAME", "nexion-user",
                "NEXION_DB_PASSWORD", "nexion-pass"));

        assertThat(resolved.source()).isEqualTo("NEXION_DB");
        assertThat(resolved.jdbcUrl()).isEqualTo("jdbc:mysql://nexion-db:3307/nexion_contract");
        assertThat(resolved.username()).isEqualTo("nexion-user");
        assertThat(resolved.password()).isEqualTo("nexion-pass");
    }

    @Test
    void resolvesCompleteLegacyBundleAtomically() {
        DatabaseEnvironmentResolver.ResolvedDatabase resolved = DatabaseEnvironmentResolver.resolve(Map.of(
                "SPRING_DATASOURCE_URL", "jdbc:mysql://legacy-db:3308/legacy_contract",
                "SPRING_DATASOURCE_USERNAME", "legacy-user",
                "SPRING_DATASOURCE_PASSWORD", "legacy-pass"));

        assertThat(resolved.source()).isEqualTo("SPRING_DATASOURCE_COMPATIBILITY");
        assertThat(resolved.jdbcUrl()).isEqualTo("jdbc:mysql://legacy-db:3308/legacy_contract");
        assertThat(resolved.username()).isEqualTo("legacy-user");
        assertThat(resolved.password()).isEqualTo("legacy-pass");
    }

    @Test
    void rejectsEveryConflictingDualBundleField() {
        Map<String, String> same = new HashMap<>(Map.of(
                "NEXION_DB_URL", "jdbc:mysql://same-db:3306/same_contract",
                "NEXION_DB_USERNAME", "same-user",
                "NEXION_DB_PASSWORD", "same-pass",
                "SPRING_DATASOURCE_URL", "jdbc:mysql://same-db:3306/same_contract",
                "SPRING_DATASOURCE_USERNAME", "same-user",
                "SPRING_DATASOURCE_PASSWORD", "same-pass"));

        for (String field : new String[] {"URL", "USERNAME", "PASSWORD"}) {
            Map<String, String> conflicting = new HashMap<>(same);
            conflicting.put("SPRING_DATASOURCE_" + field, "conflicting-" + field.toLowerCase());
            assertThatThrownBy(() -> DatabaseEnvironmentResolver.resolve(conflicting))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("conflicting database environment bundles");
        }
    }

    @Test
    void resolvesEqualDualBundlesToNexionAuthority() {
        DatabaseEnvironmentResolver.ResolvedDatabase resolved = DatabaseEnvironmentResolver.resolve(Map.of(
                "NEXION_DB_URL", "jdbc:mysql://same-db:3306/same_contract",
                "NEXION_DB_USERNAME", "same-user",
                "NEXION_DB_PASSWORD", "same-pass",
                "SPRING_DATASOURCE_URL", "jdbc:mysql://same-db:3306/same_contract",
                "SPRING_DATASOURCE_USERNAME", "same-user",
                "SPRING_DATASOURCE_PASSWORD", "same-pass"));

        assertThat(resolved.source()).isEqualTo("NEXION_DB");
    }

    @Test
    void rejectsPartialLegacyBundleInsteadOfCrossBundleAssembly() {
        assertThatThrownBy(() -> DatabaseEnvironmentResolver.resolve(Map.of(
                        "NEXION_DB_PASSWORD", "nexion-pass",
                        "SPRING_DATASOURCE_URL", "jdbc:mysql://legacy-db:3306/legacy_contract",
                        "SPRING_DATASOURCE_USERNAME", "legacy-user")))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("complete bundle");
    }

    @Test
    void rejectsPartialNexionBundleWhenLegacyBundleIsAlsoPresent() {
        assertThatThrownBy(() -> DatabaseEnvironmentResolver.resolve(Map.of(
                        "NEXION_DB_PASSWORD", "nexion-pass",
                        "SPRING_DATASOURCE_URL", "jdbc:mysql://legacy-db:3306/legacy_contract",
                        "SPRING_DATASOURCE_USERNAME", "legacy-user",
                        "SPRING_DATASOURCE_PASSWORD", "legacy-pass")))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("NEXION_DB_URL")
                .hasMessageContaining("complete bundle");
    }

    @Test
    void usesTheSameDefaultsAsApplicationYamlForNexionBundle() {
        DatabaseEnvironmentResolver.ResolvedDatabase resolved = DatabaseEnvironmentResolver.resolve(
                Map.of("NEXION_DB_PASSWORD", "nexion-defaults-pass"));

        assertThat(resolved.jdbcUrl())
                .isEqualTo("jdbc:mysql://127.0.0.1:3306/nexion?useUnicode=true&characterEncoding=utf8"
                        + "&serverTimezone=Asia/Shanghai&useSSL=false&allowPublicKeyRetrieval=true");
        assertThat(resolved.username()).isEqualTo("root");
        assertThat(resolved.password()).isEqualTo("nexion-defaults-pass");
    }

    @Test
    void applicationBootstrapAddsAuthoritativeDatasourcePropertiesWithoutJvmSystemProperties() {
        MockEnvironment environment = new MockEnvironment()
                .withProperty("spring.datasource.url", "jdbc:mysql://legacy-binding:3306/wrong_contract")
                .withProperty("spring.datasource.username", "wrong-user")
                .withProperty("spring.datasource.password", "wrong-pass");

        new DatabaseEnvironmentPostProcessor().applyAuthoritativePropertySource(environment, Map.of(
                "NEXION_DB_URL", "jdbc:mysql://runtime-db:3306/runtime_contract",
                "NEXION_DB_USERNAME", "runtime-user",
                "NEXION_DB_PASSWORD", "runtime-pass"));

        assertThat(environment.getProperty("spring.datasource.url"))
                .isEqualTo("jdbc:mysql://runtime-db:3306/runtime_contract");
        assertThat(environment.getProperty("spring.datasource.username")).isEqualTo("runtime-user");
        assertThat(environment.getProperty("spring.datasource.password")).isEqualTo("runtime-pass");
    }
}
