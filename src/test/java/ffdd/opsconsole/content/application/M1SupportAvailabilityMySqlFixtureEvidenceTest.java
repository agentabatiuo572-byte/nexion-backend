package ffdd.opsconsole.content.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.MockedStatic;

/** Pure fault injection: DriverManager is mocked for both admin and fixture connections. */
class M1SupportAvailabilityMySqlFixtureEvidenceTest {
    private static final String URL = "jdbc:mysql://127.0.0.1:33329/?useSSL=false";
    private static final String RUN = "fixture-safety-test";
    private static final String SNAPSHOT = "a".repeat(64);
    private final ObjectMapper json = new ObjectMapper();
    private final Connection admin = mock(Connection.class);
    private final Statement statements = mock(Statement.class);
    private final AtomicBoolean schemaExists = new AtomicBoolean();
    private final AtomicReference<String> schema = new AtomicReference<>();
    private final AtomicInteger creates = new AtomicInteger(), drops = new AtomicInteger();
    @TempDir Path temporary;
    private Path directory;
    private Map<String, String> environment;

    @BeforeEach void setup() throws Exception {
        directory = temporary.resolve("evidence");
        environment = new HashMap<>(Map.of(
                "NEXION_TEST_DB_SERVER_URL", URL, "NEXION_TEST_DB_USERNAME", "unit-only",
                "NEXION_TEST_DB_PASSWORD", "not-a-real-credential",
                "SUPPORT_TICKET_SCHEMA_EVIDENCE_DIR", directory.toString(),
                "WORKFLOW_RUN_ID", RUN, "WORKFLOW_SNAPSHOT_HASH", SNAPSHOT));
        DatabaseMetaData metadata = mock(DatabaseMetaData.class);
        when(admin.getMetaData()).thenReturn(metadata);
        when(metadata.getURL()).thenReturn(URL);
        when(admin.createStatement()).thenReturn(statements);
        when(statements.executeQuery("SELECT @@port")).thenAnswer(call -> scalar(33329));
        when(admin.prepareStatement("SELECT COUNT(*) FROM information_schema.SCHEMATA WHERE SCHEMA_NAME=?"))
                .thenAnswer(call -> {
                    PreparedStatement query = mock(PreparedStatement.class);
                    doAnswer(set -> { schema.set(set.getArgument(1)); return null; })
                            .when(query).setString(eq(1), anyString());
                    when(query.executeQuery()).thenAnswer(ignored -> scalar(schemaExists.get() ? 1 : 0));
                    return query;
                });
        when(statements.execute(anyString())).thenAnswer(call -> {
            String sql = call.getArgument(0);
            if (sql.startsWith("CREATE DATABASE ")) {
                creates.incrementAndGet();
                schemaExists.set(true);
            } else {
                assertThat(sql).isEqualTo("DROP DATABASE `" + schema.get() + "`");
                drops.incrementAndGet();
                schemaExists.set(false);
            }
            return false;
        });
    }

    @Test void preparedPrecedesCreateAndCreatedPrecedesAnyFixtureConnection() throws Exception {
        doAnswer(call -> {
            String sql = call.getArgument(0);
            if (sql.startsWith("CREATE DATABASE ")) {
                JsonNode intent = records("schema-intents.jsonl").get(0);
                assertIntent(intent);
                assertThat(sql).startsWith("CREATE DATABASE `" + intent.path("schema").asText() + "`");
                assertThat(Files.exists(directory.resolve("owned-schemas.jsonl"))).isFalse();
                creates.incrementAndGet(); schemaExists.set(true);
            } else {
                assertThat(sql).isEqualTo("DROP DATABASE `" + schema.get() + "`");
                drops.incrementAndGet(); schemaExists.set(false);
            }
            return false;
        }).when(statements).execute(anyString());
        try (MockedStatic<DriverManager> driver = driver()) {
            driver.when(() -> DriverManager.getConnection(anyString(), any(Properties.class))).thenAnswer(call -> {
                List<JsonNode> ledger = records("owned-schemas.jsonl");
                assertThat(ledger).hasSize(1);
                assertThat(ledger.get(0).path("action").asText()).isEqualTo("CREATED");
                assertThat(ledger.get(0).path("schemaExistsAtReadback").asBoolean()).isTrue();
                assertThat(ledger.get(0).path("schema").asText()).isEqualTo(schema.get());
                throw new SQLException("Injected fixture connection failure");
            });
            assertThatThrownBy(() -> M1SupportAvailabilityMySqlFixture.openFromEnvironment(environment))
                    .isInstanceOf(RuntimeException.class);
        }
        assertThat(creates.get()).isEqualTo(1); assertThat(drops.get()).isEqualTo(1);
        List<JsonNode> ledger = records("owned-schemas.jsonl");
        assertThat(ledger).hasSize(2);
        assertThat(ledger.get(1).path("action").asText()).isEqualTo("DROPPED");
        assertThat(ledger.get(1).path("schemaExistsAtReadback").asBoolean()).isFalse();
        verify(admin).close();
    }

    @Test void failedCreateKeepsIntentButNeverDropsOrClaimsOwnership() throws Exception {
        doThrow(new SQLException("Injected CREATE rejection")).when(statements).execute(anyString());
        try (MockedStatic<DriverManager> ignored = driver()) {
            assertThatThrownBy(() -> M1SupportAvailabilityMySqlFixture.openFromEnvironment(environment))
                    .isInstanceOf(SQLException.class).hasMessage("Injected CREATE rejection");
        }
        assertIntent(records("schema-intents.jsonl").get(0));
        assertThat(Files.exists(directory.resolve("owned-schemas.jsonl"))).isFalse();
        verify(statements, times(1)).execute(startsWith("CREATE DATABASE "));
        verify(statements, never()).execute(startsWith("DROP DATABASE "));
        verify(admin).close();
    }

    @Test void failedIntentWritePreventsCreateAndDrop() throws Exception {
        Files.writeString(directory, "not a directory");
        try (MockedStatic<DriverManager> ignored = driver()) {
            assertThatThrownBy(() -> M1SupportAvailabilityMySqlFixture.openFromEnvironment(environment))
                    .isInstanceOf(SQLException.class).hasMessageContaining("evidence could not be saved");
        }
        verify(statements, never()).execute(anyString());
        verify(admin).close();
    }

    @Test void existingExactSchemaIsNeverAdopted() throws Exception {
        schemaExists.set(true);
        try (MockedStatic<DriverManager> ignored = driver()) {
            assertThatThrownBy(() -> M1SupportAvailabilityMySqlFixture.openFromEnvironment(environment))
                    .isInstanceOf(SQLException.class).hasMessageContaining("readback failed");
        }
        assertThat(Files.exists(directory)).isFalse();
        verify(statements, never()).execute(anyString());
        verify(admin).close();
    }

    @Test void actualUnexpectedPortPreventsAnySchemaWrite() throws Exception {
        when(statements.executeQuery("SELECT @@port")).thenAnswer(call -> scalar(3306));
        try (MockedStatic<DriverManager> ignored = driver()) {
            assertThatThrownBy(() -> M1SupportAvailabilityMySqlFixture.openFromEnvironment(environment))
                    .isInstanceOf(SQLException.class).hasMessageContaining("port is not authorized");
        }
        assertThat(Files.exists(directory)).isFalse();
        verify(statements, never()).execute(anyString());
        verify(admin).close();
    }

    @Test void invalidNativeIdentityPreventsConnection() throws Exception {
        environment.put("WORKFLOW_SNAPSHOT_HASH", "invalid");
        try (MockedStatic<DriverManager> driver = driver()) {
            assertThatThrownBy(() -> M1SupportAvailabilityMySqlFixture.openFromEnvironment(environment))
                    .isInstanceOf(SQLException.class).hasMessageContaining("Current native identity");
            driver.verify(() -> DriverManager.getConnection(anyString(), anyString(), anyString()), never());
            driver.verify(() -> DriverManager.getConnection(anyString(), any(Properties.class)), never());
            driver.verify(() -> DriverManager.getConnection(anyString()), never());
        }
    }

    @Test void createdJournalFailureStillDropsOnlyTheSuccessfullyCreatedName() throws Exception {
        doAnswer(call -> {
            String sql = call.getArgument(0);
            if (sql.startsWith("CREATE DATABASE ")) {
                creates.incrementAndGet(); schemaExists.set(true);
                Files.createDirectory(directory.resolve("owned-schemas.jsonl"));
            } else {
                assertThat(sql).isEqualTo("DROP DATABASE `" + schema.get() + "`");
                drops.incrementAndGet(); schemaExists.set(false);
            }
            return false;
        }).when(statements).execute(anyString());
        try (MockedStatic<DriverManager> ignored = driver()) {
            assertThatThrownBy(() -> M1SupportAvailabilityMySqlFixture.openFromEnvironment(environment))
                    .isInstanceOf(SQLException.class).hasMessageContaining("evidence could not be saved")
                    .satisfies(failure -> assertThat(failure.getSuppressed()).hasSize(1));
        }
        assertIntent(records("schema-intents.jsonl").get(0));
        assertThat(creates.get()).isEqualTo(1); assertThat(drops.get()).isEqualTo(1);
        assertThat(schemaExists.get()).isFalse();
        verify(admin).close();
    }

    @Test void abruptUnknownCreateLeavesOnlyPreciseNonOwnershipIntent() throws Exception {
        doAnswer(call -> { schemaExists.set(true); throw new AbruptTermination(); })
                .when(statements).execute(startsWith("CREATE DATABASE "));
        try (MockedStatic<DriverManager> ignored = driver()) {
            assertThatThrownBy(() -> M1SupportAvailabilityMySqlFixture.openFromEnvironment(environment))
                    .isInstanceOf(AbruptTermination.class);
        }
        List<JsonNode> intents = records("schema-intents.jsonl");
        assertThat(intents).hasSize(1); assertIntent(intents.get(0));
        assertThat(intents.get(0).path("schema").asText()).isEqualTo(schema.get());
        assertThat(Files.exists(directory.resolve("owned-schemas.jsonl"))).isFalse();
        verify(statements, never()).execute(startsWith("DROP DATABASE "));
    }

    private MockedStatic<DriverManager> driver() throws SQLException {
        // SQLException consults DriverManager's log writer; construct it before static stubbing.
        SQLException connectionFailure = new SQLException("Injected fixture connection failure");
        MockedStatic<DriverManager> driver = mockStatic(DriverManager.class);
        try {
            driver.when(() -> DriverManager.getConnection(URL, "unit-only", "not-a-real-credential")).thenReturn(admin);
            driver.when(() -> DriverManager.getConnection(anyString(), any(Properties.class)))
                    .thenThrow(connectionFailure);
            return driver;
        } catch (RuntimeException | Error failure) {
            driver.close();
            throw failure;
        }
    }

    private ResultSet scalar(int value) throws SQLException {
        ResultSet rows = mock(ResultSet.class);
        when(rows.next()).thenReturn(true, false);
        when(rows.getInt(1)).thenReturn(value);
        return rows;
    }

    private List<JsonNode> records(String file) throws Exception {
        var result = new java.util.ArrayList<JsonNode>();
        for (String line : Files.readAllLines(directory.resolve(file))) result.add(json.readTree(line));
        return result;
    }

    private void assertIntent(JsonNode intent) {
        assertThat(intent.path("action").asText()).isEqualTo("PREPARED");
        assertThat(intent.path("schema").asText()).matches("nx_m1_support_availability_test_[0-9a-f]{32}");
        assertThat(intent.path("runId").asText()).isEqualTo(RUN);
        assertThat(intent.path("snapshotHash").asText()).isEqualTo(SNAPSHOT);
        assertThat(intent.path("databasePort").asInt()).isEqualTo(33329);
        assertThat(intent.path("schemaExistsAtReadback").asBoolean()).isFalse();
        assertThat(intent.path("successfulCreate").asBoolean()).isFalse();
        assertThat(intent.path("ownsSchema").asBoolean()).isFalse();
    }

    private static final class AbruptTermination extends Error { }
}
