package ffdd.opsconsole.bi.application;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

class L4RbacSeedContractTest {
    @Test
    void l4RoleMatrixMatchesTheProductContract() throws Exception {
        String seed = Files.readString(
                Path.of("scripts/rbac-classic-seed/02-role-permission-seed.sql"),
                StandardCharsets.UTF_8).replaceAll("\\s+", " ");

        assertThat(seed)
                .contains("WHERE r.role_code='GROWTH' AND p.permission_code IN ('bi_l1_read','bi_l1_write','bi_l2_read','bi_l2_write','bi_l4_read','bi_l4_write','bi_l4_export_tree','bi_l5_read','bi_l6_read')")
                .contains("WHERE r.role_code='RISK' AND p.permission_code IN ('bi_l3_read','bi_l4_read','bi_l5_read','bi_l5_task_approve','bi_l5_regulatory_generate','bi_l6_read')")
                .contains("'bi_l4_read','bi_l4_write','bi_l4_export_tree','bi_l5_read','bi_l6_read'");
    }
}
