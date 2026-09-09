package ffdd.opsconsole.shared.outbox;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

/** Pure fixture-target guard: no JDBC connection is opened by these assertions. */
class H3DeadLetterRedriveMySqlUrlGuardTest {

    @Test
    void rejectsBusinessCatalogAndRemoteHostsBeforeAnyFixtureDdl() {
        assertThatThrownBy(() -> H3DeadLetterRedriveSpringTransactionMySqlIntegrationTest
                .requireServerUrlWithoutCatalog("jdbc:mysql://127.0.0.1:3306/nexion"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> H3DeadLetterRedriveSpringTransactionMySqlIntegrationTest
                .requireServerUrlWithoutCatalog("jdbc:mysql://192.168.8.10:3306/"))
                .isInstanceOf(IllegalArgumentException.class);
        H3DeadLetterRedriveSpringTransactionMySqlIntegrationTest
                .requireServerUrlWithoutCatalog("jdbc:mysql://127.0.0.1:3306/?serverTimezone=Asia%2FShanghai");
    }
}
