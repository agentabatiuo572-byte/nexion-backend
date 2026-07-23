package ffdd.opsconsole.growth.application;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

class H3DeviceActivationAuthorityMigrationContractTest {

    private final String sql = readMigration();

    @Test
    void promotesOnlyTheActiveDeviceActivationRegistryContractToTrustedServerAuthority() {
        assertThat(sql).contains(
                "UPDATE nx_event_schema_registry",
                "is_server_authoritative=1",
                "updated_by='migration:h3-trusted-device-activation'",
                "reason='H3 trusted producer: E5 OpsDeviceService device activation'",
                "event_name='admin.device_activated'",
                "status='ACTIVE'",
                "is_deleted=0",
                "UPDATE nx_event_domain_extension",
                "consumer='E5/A2/A4/L3/H3'",
                "status='REGISTERED'");
    }

    @Test
    void doesNotRewriteOrReplayPreviouslyDeadOutboxEvents() {
        assertThat(sql).doesNotContain(
                "nx_event_outbox",
                "status='PENDING'",
                "status = 'PENDING'",
                "retry_count",
                "last_error");
    }

    private static String readMigration() {
        try {
            return Files.readString(
                    Path.of("scripts/migrations/20260722_h3_device_activation_authority.sql"),
                    StandardCharsets.UTF_8);
        } catch (Exception ex) {
            throw new IllegalStateException(ex);
        }
    }
}
