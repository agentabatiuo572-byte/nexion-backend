package ffdd.opsconsole.growth.mapper;
import static org.assertj.core.api.Assertions.*;
import org.junit.jupiter.api.Test;
class H3RegistrationFixtureSafetyTest {
    @Test void requiresExplicitOptInAndExactIsolatedEndpointWithoutBusinessCatalog() {
        for (String optedIn : new String[]{null, "", "false"})
            assertThatThrownBy(() -> H3RegistrationBindingMySqlIntegrationTest.requireIsolatedServer(optedIn,
                    "jdbc:mysql://127.0.0.1:33317/")).isInstanceOf(IllegalArgumentException.class);
        for (String url : new String[]{null, "jdbc:mysql://localhost:33317/", "jdbc:mysql://127.0.0.1:3306/",
                "jdbc:mysql://127.0.0.1:33317/nexion", "jdbc:mysql://18.142.169.24:33317/"})
            assertThatThrownBy(() -> H3RegistrationBindingMySqlIntegrationTest.requireIsolatedServer("true",url))
                    .isInstanceOf(IllegalArgumentException.class);
        assertThatCode(() -> H3RegistrationBindingMySqlIntegrationTest.requireIsolatedServer("true",
                "jdbc:mysql://127.0.0.1:33317/?useSSL=false")).doesNotThrowAnyException();
    }
}
