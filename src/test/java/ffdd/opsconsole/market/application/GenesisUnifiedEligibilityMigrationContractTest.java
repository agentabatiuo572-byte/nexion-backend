package ffdd.opsconsole.market.application;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

class GenesisUnifiedEligibilityMigrationContractTest {

    private static final Path MIGRATION = Path.of(
            "scripts", "migrations", "20260827_genesis_unified_eligibility.sql");

    @Test
    void cleanSchemaAndUpgradeMigrationSeedTheServerAuthoritativePolicy() throws Exception {
        String schema = Files.readString(Path.of("scripts", "schema.sql"), StandardCharsets.UTF_8);
        String migration = Files.readString(MIGRATION, StandardCharsets.UTF_8);

        for (String key : new String[] {
                "market.genesis.ops.eligibility.enabled",
                "market.genesis.ops.eligibility.maxPerUser",
                "market.genesis.ops.eligibility.minAccountAgeDays",
                "market.genesis.ops.presale.enabled",
                "market.genesis.ops.presale.showCountdown",
                "market.genesis.ops.presale.unitPrice",
                "market.genesis.ops.presale.maxPerUser"
        }) {
            assertThat(schema).contains("'" + key + "'");
            assertThat(migration).contains("'" + key + "'");
        }
        assertThat(migration)
                .contains("WHERE NOT EXISTS")
                .contains("existing.config_key=seed.config_key")
                .contains("status=0")
                .contains("is_deleted=1")
                .doesNotContain("ON DUPLICATE KEY UPDATE");
    }

    @Test
    void upgradeMigrationNeverReactivatesAnExistingCanonicalRow() throws Exception {
        String migration = Files.readString(MIGRATION, StandardCharsets.UTF_8);

        assertThat(migration)
                .contains("existing.config_key='market.genesis.ops.eligibility.maxPerUser'")
                .doesNotContain("status=VALUES(status)")
                .doesNotContain("status=1,is_deleted=0");
    }

    @Test
    void migrationRetiresEveryLegacyGenesisAnyOfFieldAndRunsAtStartup() throws Exception {
        String migration = Files.readString(MIGRATION, StandardCharsets.UTF_8);
        String startup = Files.readString(
                Path.of("scripts", "apply_startup_schema_migrations.ps1"), StandardCharsets.UTF_8);

        for (String key : new String[] {
                "market.genesis.ops.eligibility.mode",
                "market.genesis.ops.eligibility.minDepositUsdt",
                "market.genesis.ops.eligibility.flagshipMin",
                "market.genesis.ops.eligibility.vRankMin",
                "market.genesis.ops.eligibility.inviteEnabled",
                "market.genesis.ops.eligibility.perUserCap",
                "market.genesis.ops.eligibility.appliesTo"
        }) {
            assertThat(migration).contains("'" + key + "'");
        }
        assertThat(startup).contains("20260827_genesis_unified_eligibility.sql");
    }
}
