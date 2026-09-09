package ffdd.opsconsole.growth.mapper;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

/** Pure URL ownership guard; deliberately no environment or JDBC dependency. */
class H3DayOneSnapshotMapperUrlGuardTest {

    @Test
    void rejectsBusinessCatalogAndRemoteHostsWithoutOpeningAnyConnection() {
        assertThat(org.assertj.core.api.Assertions.catchThrowable(
                () -> H3DayOneSnapshotMapperMySqlIntegrationTest
                        .requireServerUrlWithoutCatalog("jdbc:mysql://127.0.0.1:3306/nexion")))
                .isInstanceOf(IllegalStateException.class);
        assertThat(org.assertj.core.api.Assertions.catchThrowable(
                () -> H3DayOneSnapshotMapperMySqlIntegrationTest
                        .requireServerUrlWithoutCatalog("jdbc:mysql://192.168.8.10:3306/")))
                .isInstanceOf(IllegalStateException.class);
        H3DayOneSnapshotMapperMySqlIntegrationTest
                .requireServerUrlWithoutCatalog("jdbc:mysql://127.0.0.1:3306/?serverTimezone=Asia%2FShanghai");
    }
}
