package ffdd.opsconsole.device.application;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

class E5DeviceOperationsMigrationContractTest {

    private final String sql = readMigration();

    @Test
    void registersTrustedActivationAsAuthoritativeButKeepsOtherManualEventsNonAuthoritative() {
        assertThat(sql).contains(
                "('admin.device_activated','admin','device-ops','server','E5/A2/A4/L3/H3',1",
                "'migration:h3-trusted-device-activation'",
                "'E5 trusted server device activation for H3 canonical quest'",
                "('admin','admin.device_activated','OpsDeviceService','E5/A2/A4/L3/H3','REGISTERED'",
                "('admin.device_paused','admin','device-ops','server','E5/A2/A4/L3',0",
                "('admin.device_resumed','admin','device-ops','server','E5/A2/A4/L3',0",
                "('admin.device_deactivated','admin','device-ops','server','E5/A2/A4/L3',0",
                "('admin.datacenter_created','admin','device-datacenter','server','E5/A2/A4',0",
                "is_server_authoritative=VALUES(is_server_authoritative)");
    }

    @Test
    void registersRequiredE5PayloadAndLatestRevision() {
        assertThat(sql).contains(
                "'device_ids'",
                "'scope'",
                "'mode'",
                "'display_name'",
                "current_revision=GREATEST(current_revision,57)");
    }

    @Test
    void grantsGrowthOnlyTheMinimumA2ProposalAndReadAccessForE5() {
        assertThat(sql).contains(
                "role_row.role_code = 'GROWTH'",
                "permission_row.permission_code IN ('platform_a2_read', 'platform_a2_proposal_create')",
                "menu_row.menu_code IN ('A', 'A2')",
                "SET role_permission.is_deleted = 1",
                "'device_e5_write'",
                "'device_e5_device_force_activate'",
                "'device_e5_device_unbind'",
                "'device_e5_datacenter_pause'");
        assertThat(sql).doesNotContain(
                "permission_row.permission_code IN ('platform_a2_write', 'platform_a2_operation_approve')");
    }

    private static String readMigration() {
        try {
            return Files.readString(
                    Path.of("scripts/migrations/20260721_e5_device_operations.sql"),
                    StandardCharsets.UTF_8);
        } catch (Exception ex) {
            throw new IllegalStateException(ex);
        }
    }
}
