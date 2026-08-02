package ffdd.opsconsole.team.application;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

class F5CommissionAnomalyEventSchemaMigrationContractTest {
    private static final Path MIGRATION =
            Path.of("scripts/migrations/20260730_f5_commission_anomaly_event_schema.sql");

    @Test
    void revision303RegistersTheExactCanonicalF5ProducerPayload() throws Exception {
        String migration = normalized(MIGRATION);

        assertThat(migration)
                .contains("'admin.commission_anomaly_config_changed'")
                .contains("'phase_admin'")
                .contains("'F5CommissionService'")
                .contains("'100%',303,'ACTIVE'")
                .contains("SELECT 'before_commission_anomaly_sigma' property_name,'number' property_type,1 required_field")
                .contains("SELECT 'after_commission_anomaly_sigma','number',1")
                .contains("SELECT 'before_layer_ratio_anomaly_pct','number',1")
                .contains("SELECT 'after_layer_ratio_anomaly_pct','number',1")
                .contains("SELECT 'operator','string',1")
                .contains("SELECT 'reason','string',1");
    }

    @Test
    void producerAndRegistryUseTheSameEventNameAndRequiredFields() throws Exception {
        String migration = normalized(MIGRATION);
        String producer = normalized(
                Path.of("src/main/java/ffdd/opsconsole/team/application/F5CommissionService.java"));

        assertThat(producer)
                .contains("\"beforeCommissionAnomalySigma\", beforeSigma")
                .contains("\"afterCommissionAnomalySigma\", sigma")
                .contains("\"beforeLayerRatioAnomalyPct\", beforeLayer")
                .contains("\"afterLayerRatioAnomalyPct\", layerRatio")
                .contains("\"operator\", operator")
                .contains("\"reason\", reason")
                .contains("\"admin.commission_anomaly_config_changed\", detail");
        assertThat(migration)
                .contains("'before_commission_anomaly_sigma'")
                .contains("'after_commission_anomaly_sigma'")
                .contains("'before_layer_ratio_anomaly_pct'")
                .contains("'after_layer_ratio_anomaly_pct'")
                .contains("'operator'")
                .contains("'reason'");
    }

    @Test
    void repeatedStartupIsIdempotentAndIncludesTheMigrationExactlyOnce() throws Exception {
        String migration = normalized(MIGRATION);
        String startup = normalized(Path.of("scripts/apply_startup_schema_migrations.ps1"));

        assertThat(migration)
                .contains("ON DUPLICATE KEY UPDATE")
                .contains("UPDATE nx_event_schema_property p")
                .contains("INSERT INTO nx_event_schema_revision (id,current_revision) VALUES (1,303)")
                .doesNotContain("DELETE FROM nx_event_schema_property");
        assertThat(countOccurrences(startup, "20260730_f5_commission_anomaly_event_schema.sql"))
                .isEqualTo(1);
    }

    @Test
    void futureSchemaVersionIsNotDowngradedAndUnknownPropertiesFailClosed() throws Exception {
        String migration = normalized(MIGRATION);
        String mapper = normalized(
                Path.of("src/main/java/ffdd/opsconsole/shared/outbox/mapper/EventOutboxMapper.java"));

        assertThat(migration)
                .contains("owner_domain=IF(current_revision<=303,VALUES(owner_domain),owner_domain)")
                .contains("family_key=IF(current_revision<=303,VALUES(family_key),family_key)")
                .contains("producer=IF(current_revision<=303,VALUES(producer),producer)")
                .contains("consumers=IF(current_revision<=303,VALUES(consumers),consumers)")
                .contains("is_server_authoritative=IF(current_revision<=303,VALUES(is_server_authoritative),is_server_authoritative)")
                .contains("sampling_policy=IF(current_revision<=303,VALUES(sampling_policy),sampling_policy)")
                .contains("status=IF(current_revision<=303,VALUES(status),status)")
                .contains("is_deleted=IF(current_revision<=303,VALUES(is_deleted),is_deleted)")
                .contains("current_revision=GREATEST(current_revision,303)")
                .contains("s.current_revision=303");
        assertThat(mapper)
                .contains("p.registry_revision = s.current_revision");
    }

    private String normalized(Path path) throws Exception {
        return Files.readString(path, StandardCharsets.UTF_8).replaceAll("\\s+", " ");
    }

    private int countOccurrences(String value, String needle) {
        return (value.length() - value.replace(needle, "").length()) / needle.length();
    }
}
