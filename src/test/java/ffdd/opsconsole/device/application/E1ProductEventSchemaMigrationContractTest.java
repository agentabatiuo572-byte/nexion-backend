package ffdd.opsconsole.device.application;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

class E1ProductEventSchemaMigrationContractTest {
    private final String sql = readMigration();

    @Test
    void migrationRegistersTheThreeGovernedE1ProductEvents() {
        assertThat(sql).contains(
                "'admin.product_listed'",
                "'admin.product_unlisted'",
                "'admin.product_price_changed'",
                "'admin'",
                "'phase_admin'",
                "'server'",
                "'E1/A2/L3/B4'",
                "ON DUPLICATE KEY UPDATE");
    }

    @Test
    void migrationRegistersTheLockedPrdPayloadProperties() {
        assertThat(sql).contains(
                "'sku_key'",
                "'before_status'",
                "'after_status'",
                "'operator'",
                "'reason'",
                "'scope'",
                "'field'",
                "'before'",
                "'after'",
                "'effective_at'");
    }

    @Test
    void migrationAdvancesTheRegistryRevisionAndBackfillsLegacyInternalEventNames() {
        assertThat(sql).contains(
                "INSERT INTO nx_event_schema_revision",
                "current_revision=GREATEST(current_revision,42)",
                "WHEN 'PRODUCT_LISTED' THEN 'admin.product_listed'",
                "WHEN 'PRODUCT_UNLISTED' THEN 'admin.product_unlisted'",
                "WHEN 'PRODUCT_PRICE_CHANGED' THEN 'admin.product_price_changed'",
                "schema_registered=1",
                "analytics_event=1");
    }

    private static String readMigration() {
        try {
            return Files.readString(
                    Path.of("scripts/migrations/20260720_e1_product_event_schema.sql"),
                    StandardCharsets.UTF_8);
        } catch (Exception ex) {
            throw new IllegalStateException(ex);
        }
    }
}
