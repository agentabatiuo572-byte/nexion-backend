package ffdd.opsconsole.team.application;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

class F3BinarySettlementMigrationContractTest {
    @Test
    void migrationOwnsImmutableLegsIdempotentPaidVolumesAndDailyMutex() throws Exception {
        String sql = Files.readString(Path.of(
                "scripts/migrations/20260722_f3_binary_settlement.sql"));

        assertThat(sql).contains("CREATE TABLE IF NOT EXISTS nx_binary_leg_assignment");
        assertThat(sql).contains("UNIQUE KEY uk_binary_leg_owner_member");
        assertThat(sql).contains("CHECK (leg IN ('A','B'))");
        assertThat(sql).contains("assigned_by_admin_id BIGINT NOT NULL");
        assertThat(sql).contains("CREATE TABLE IF NOT EXISTS nx_binary_paid_order_volume");
        assertThat(sql).contains("UNIQUE KEY uk_binary_paid_order_owner");
        assertThat(sql).contains("status VARCHAR(32) NOT NULL DEFAULT 'ACTIVE'");
        assertThat(sql).contains("is_deleted TINYINT NOT NULL DEFAULT 0");
        assertThat(sql).contains("CREATE TABLE IF NOT EXISTS nx_binary_settlement_mutex");
        assertThat(sql).contains("UNIQUE KEY uk_binary_settlement_mutex");
        assertThat(sql).contains("team.ui.F.binary.paused", "false");
    }
}
