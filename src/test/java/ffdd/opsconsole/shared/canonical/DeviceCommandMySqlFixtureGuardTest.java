package ffdd.opsconsole.shared.canonical;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

class DeviceCommandMySqlFixtureGuardTest {
    @Test
    void rejectsBusinessEndpointsAndUnownedSchemasBeforeConnecting() {
        String schema = "nx_device_event_test_" + "a".repeat(32);
        assertThat(DeviceCommandEventMySqlIntegrationTest.isolatedUrl("127.0.0.1:13306", schema))
                .startsWith("jdbc:mysql://127.0.0.1:13306/" + schema + "?");
        for (String endpoint : new String[]{null, "", "localhost:13306", "127.0.0.1:3306",
                "127.0.0.1:013306", "127.0.0.1:13306/nexion", "127.0.0.1:13306?x=y"}) {
            assertThatThrownBy(() -> DeviceCommandEventMySqlIntegrationTest.isolatedUrl(endpoint, schema))
                    .isInstanceOf(IllegalArgumentException.class);
        }
        for (String database : new String[]{null, "nexion", "mysql", schema + "`", schema.toUpperCase(), schema + "/x"}) {
            assertThatThrownBy(() -> DeviceCommandEventMySqlIntegrationTest.isolatedUrl("127.0.0.1:13306", database))
                    .isInstanceOf(IllegalArgumentException.class);
        }
    }

    @Test
    void readsOnlyTheTwelveRequiredTableDefinitionsFromVersionedScripts() throws Exception {
        assertThat(DeviceCommandEventMySqlIntegrationTest.fixtureStatements()).hasSize(12).allSatisfy(ddl ->
                assertThat(ddl).startsWith("CREATE TABLE IF NOT EXISTS nx_")
                        .doesNotContain("CREATE DATABASE", "USE nexion", "INSERT INTO", "UPDATE nx_"));
    }

    @Test
    void schemaSelectionFailsClosedForUnknownMissingOrAmbiguousTables() {
        String ddl = "CREATE TABLE IF NOT EXISTS nx_user (\n  id BIGINT PRIMARY KEY\n) ENGINE=InnoDB;";
        assertThat(DeviceCommandEventMySqlIntegrationTest.createTableStatement(
                "CREATE DATABASE nexion;\nUSE nexion;\n" + ddl + "\nINSERT INTO nx_user VALUES(1);", "nx_user"))
                .isEqualTo(ddl);
        assertThatThrownBy(() -> DeviceCommandEventMySqlIntegrationTest.createTableStatement(ddl, "nx_wallet_ledger"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> DeviceCommandEventMySqlIntegrationTest.createTableStatement("", "nx_user"))
                .isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> DeviceCommandEventMySqlIntegrationTest.createTableStatement(ddl + "\n" + ddl, "nx_user"))
                .isInstanceOf(IllegalStateException.class);
    }
}
