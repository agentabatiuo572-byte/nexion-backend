package ffdd.opsconsole.shared.canonical;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

class DeviceCommandEventSchemaMigrationContractTest {
    static final String MIGRATION = "20260831_app_device_command_event_schema.sql";

    @Test
    void bothDeviceCommandsRegisterTheirExactCanonicalFieldsAndLifecycle() throws Exception {
        String sql = Files.readString(Path.of("scripts/migrations", MIGRATION));
        assertThat(sql).contains("START TRANSACTION;", "COMMIT;", "'device.activated'",
                "'device.deactivated'", "'device_id'", "'instance_no'", "'previous_status'",
                "'status'", "'row_version'", "nx_event_schema_property", "nx_admin_event_lifecycle",
                "'full'", "GREATEST(current_revision,315)");
        assertThat(sql).doesNotContain("UPDATE nx_user_device", "UPDATE nx_user_wallet",
                "UPDATE nx_compute_task", "DELETE FROM");
    }

    @Test
    void normalStartupIncludesTheMigrationAndDoesNotMakeItOptional() throws Exception {
        String runner = Files.readString(Path.of("scripts/apply_startup_schema_migrations.ps1"));
        assertThat(runner).contains(MIGRATION).doesNotContain("SkipDeviceCommandEvent");
        assertThat(runner.indexOf(MIGRATION))
                .isGreaterThan(runner.indexOf("20260811_a2_a4_runtime_policy_closure.sql"));
    }
}
