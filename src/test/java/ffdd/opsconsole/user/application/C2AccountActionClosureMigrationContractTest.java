package ffdd.opsconsole.user.application;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

class C2AccountActionClosureMigrationContractTest {
    @Test
    void c2RoleMatrixReversibleFinanceLinkAndCanonicalEventsAreDurablySeeded() throws Exception {
        String migration = Files.readString(
                Path.of("scripts/migrations/20260718_c2_account_action_closure.sql"),
                StandardCharsets.UTF_8).replaceAll("\\s+", " ");

        assertThat(migration)
                .contains("c2_previous_status")
                .contains("c2_frozen_by_user_status")
                .contains("admin.user_frozen")
                .contains("admin.user_unfrozen")
                .contains("admin.user_impersonation_started")
                .contains("admin.user_impersonation_ended")
                .contains("target_user_id")
                .contains("duration_sec")
                .contains("c2-high-risk-admin-alert")
                .contains("VALUES (1, 28)");
    }
}
