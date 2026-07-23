package ffdd.opsconsole.device.application;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

class E4OrderEventSchemaMigrationContractTest {

    @Test
    void migrationRegistersBothRefundEventsAndTheirRequiredPayload() throws IOException {
        String sql = Files.readString(Path.of("scripts/migrations/20260721_e4_order_state_machine.sql"));

        assertThat(sql)
                .contains("'order.refunded'")
                .contains("'admin.order_refunded'")
                .contains("'refund_channel'")
                .contains("'cumulative_deposit_adjusted'")
                .contains("INSERT INTO nx_event_domain_extension")
                .contains("INSERT INTO nx_event_schema_revision");
    }
}
