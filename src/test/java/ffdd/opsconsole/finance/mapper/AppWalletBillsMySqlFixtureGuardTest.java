package ffdd.opsconsole.finance.mapper;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

class AppWalletBillsMySqlFixtureGuardTest {
    @Test
    void acceptsOnlyDisposableEndpointAndOwnedSchemasBeforeAnyConnection() {
        String schema = "nx_wallet_bills_test_" + "a".repeat(32);
        assertThat(AppWalletBillsMySqlIntegrationTest.isolatedUrl("127.0.0.1:13306", schema))
                .startsWith("jdbc:mysql://127.0.0.1:13306/" + schema + "?");
        assertThat(AppWalletBillsMySqlIntegrationTest.isolatedUrl("127.0.0.1:13306", ""))
                .startsWith("jdbc:mysql://127.0.0.1:13306/?");
        for (String endpoint : new String[]{null, "", "localhost:13306", "127.0.0.1:3306",
                "127.0.0.1:013306", "127.0.0.1:13306/nexion", "127.0.0.1:13306?x=y"}) {
            assertThatThrownBy(() -> AppWalletBillsMySqlIntegrationTest.isolatedUrl(endpoint, schema))
                    .isInstanceOf(IllegalArgumentException.class);
        }
        for (String database : new String[]{null, "nexion", "mysql", schema + "`", schema.toUpperCase(), schema + "/x"}) {
            assertThatThrownBy(() -> AppWalletBillsMySqlIntegrationTest.isolatedUrl("127.0.0.1:13306", database))
                    .isInstanceOf(IllegalArgumentException.class);
        }
    }
}
