package ffdd.opsconsole.team.application;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

class LeadershipPoolBootstrapMigrationContractTest {

    @Test
    void startupAtomicallyBackfillsAllFiveKeysWithoutExecutableMoneyDefaults() throws Exception {
        String migration = Files.readString(Path.of(
                "scripts/migrations/20260811_f15_leadership_pool_authoritative_config.sql"));
        String runner = Files.readString(Path.of("scripts/apply_startup_schema_migrations.ps1"));

        assertThat(migration).contains(
                "START TRANSACTION", "COMMIT", "ON DUPLICATE KEY UPDATE",
                "SET @f15_has_authoritative_version",
                "TRIM(config_value) REGEXP '^[1-9][0-9]*$'",
                "@f15_has_authoritative_version = 1",
                "NULLIF(TRIM(config_value), '') IS NOT NULL");
        assertThat(migration).contains(
                "team.ui.F.pool.configVersion",
                "team.ui.F.pool.ratio",
                "team.ui.F.pool.unlockVRank",
                "team.ui.F.pool.monthlyCap",
                "team.ui.F.pool.settleCron");
        assertThat(migration).contains("'team.ui.F.pool.configVersion', '1'");
        assertThat(count(migration, "__UNCONFIGURED__")).isGreaterThanOrEqualTo(4);
        assertThat(migration).contains("all unversioned legacy values (including apparently valid ones)");
        assertThat(migration).doesNotContain("'team.ui.F.pool.ratio', '5", "'team.ui.F.pool.monthlyCap', '5000");
        assertThat(runner).contains("20260811_f15_leadership_pool_authoritative_config.sql");
    }

    @Test
    void configurationWriteAndSettlementReadUseTheSameRateParser() throws Exception {
        String opsTeam = Files.readString(Path.of(
                "src/main/java/ffdd/opsconsole/team/application/OpsTeamService.java"));
        String guard = Files.readString(Path.of(
                "src/main/java/ffdd/opsconsole/team/application/LeadershipPoolConfigGuard.java"));

        assertThat(opsTeam).contains(
                "LeadershipPoolConfigGuard.parseConfiguredRate(value)",
                "LeadershipPoolConfigGuard.canonicalConfiguredPercent(value)",
                "LeadershipPoolConfigGuard.parseConfiguredRate(ratioLabel)",
                "LeadershipPoolConfigGuard.isConfiguredRateIncrease(oldValue, newValue)");
        assertThat(opsTeam).doesNotContain("percentRatio(configText(\"F.pool.ratio\"");
        assertThat(guard).contains("return parseConfiguredRate(value)", "MAX_SETTLEMENT_PERCENT");
    }

    @Test
    void failClosedConfigAlertUsesAStartupRegisteredCanonicalSchema() throws Exception {
        String migration = Files.readString(Path.of(
                "scripts/migrations/20260811_f15_leadership_pool_config_blocked_event_schema.sql"));
        String runner = Files.readString(Path.of("scripts/apply_startup_schema_migrations.ps1"));

        assertThat(runner).contains("20260811_f15_leadership_pool_config_blocked_event_schema.sql");
        assertThat(migration).contains(
                "leadership_pool.settlement_blocked",
                "LeadershipPoolConfigAlertService",
                "is_server_authoritative",
                "'source'", "'config_key'", "'reason'", "'value_fingerprint'", "'blocked_at'",
                "ON DUPLICATE KEY UPDATE",
                "current_revision=GREATEST");
    }

    private int count(String text, String needle) {
        int result = 0;
        int offset = 0;
        while ((offset = text.indexOf(needle, offset)) >= 0) {
            result++;
            offset += needle.length();
        }
        return result;
    }
}
