package ffdd.opsconsole.growth.application;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

/** Pure fixture-target guard: no JDBC connection is opened by these assertions. */
class H3WeeklyParticipationMySqlUrlGuardTest {

    @Test
    void rejectsBusinessCatalogAndRemoteHostsBeforeAnyFixtureDdl() {
        assertThatThrownBy(() -> H3WeeklyParticipationMySqlIntegrationTest
                .requireServerUrlWithoutCatalog("jdbc:mysql://localhost:3306/nexion"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> H3WeeklyParticipationMySqlIntegrationTest
                .requireServerUrlWithoutCatalog("jdbc:mysql://db.internal:3306/"))
                .isInstanceOf(IllegalArgumentException.class);
        H3WeeklyParticipationMySqlIntegrationTest
                .requireServerUrlWithoutCatalog("jdbc:mysql://[::1]:3306/?useSSL=false");
    }
}
