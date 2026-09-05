package ffdd.opsconsole.shared.canonical;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

class BundleDiscountMigrationContractTest {
    @Test
    void seedsTheRealConfigItemColumnsAndRemainsRerunnable() throws Exception {
        String sql = Files.readString(Path.of(
                "scripts/migrations/20260902_bundle_discount_authority.sql"));

        assertThat(sql)
                .contains("config_group, visibility, remark")
                .contains("WHERE NOT EXISTS")
                .doesNotContain("config_group, source, remark");
    }
}
