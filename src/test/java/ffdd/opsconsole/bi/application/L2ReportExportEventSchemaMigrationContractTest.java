package ffdd.opsconsole.bi.application;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

class L2ReportExportEventSchemaMigrationContractTest {
    private static final Path MIGRATION =
            Path.of("scripts/migrations/20260729_l2_report_export_artifact_event_schema.sql");

    @Test
    void cleanRegistryGetsRevision302WithLegacyAndOptionalArtifactProperties() throws Exception {
        String migration = normalizedMigration();

        assertThat(migration)
                .contains("'admin.report_exported'")
                .contains("'phase_admin'")
                .contains("'100%',302,'ACTIVE'")
                .contains("SELECT 'report_id' property_name,'id' property_type,1 required_field")
                .contains("SELECT 'export_type','enum',1")
                .contains("SELECT 'scope','string',1")
                .contains("SELECT 'row_count','number',1")
                .contains("SELECT 'contains_pii','boolean',1")
                .contains("SELECT 'masking_policy','enum',1")
                .contains("SELECT 'operator','string',1")
                .contains("SELECT 'reason','string',1")
                .contains("SELECT 'format','enum',1")
                .contains("SELECT 'template_code','enum',0")
                .contains("SELECT 'jurisdiction_code','string',0")
                .contains("SELECT 'disclosure_version','string',0")
                .contains("SELECT 'artifact_store','string',0")
                .contains("SELECT 'artifact_sha256','string',0")
                .contains("SELECT 'artifact_size_bytes','number',0");
    }

    @Test
    void revision289IsUpgradedWithoutDroppingAnyLegacyProperty() throws Exception {
        String migration = normalizedMigration();

        assertThat(migration)
                .contains("ON DUPLICATE KEY UPDATE")
                .contains("current_revision=GREATEST(current_revision,302)")
                .contains("s.current_revision=302")
                .contains("p.property_name NOT IN ( 'report_id','export_type','scope','row_count','contains_pii', 'masking_policy','operator','reason','format', 'template_code','jurisdiction_code','disclosure_version', 'artifact_store','artifact_sha256','artifact_size_bytes' )")
                .contains("property_type=VALUES(property_type),pii=0,required_field=VALUES(required_field)")
                .contains("registry_revision=302,is_deleted=0")
                .contains("current_revision=GREATEST(current_revision,302)");
    }

    @Test
    void repeatedStartupUsesTheSameIdempotentMigrationExactlyOnce() throws Exception {
        String migration = normalizedMigration();
        String startup = Files.readString(
                Path.of("scripts/apply_startup_schema_migrations.ps1"),
                StandardCharsets.UTF_8);

        assertThat(migration)
                .contains("ON DUPLICATE KEY UPDATE")
                .contains("UPDATE nx_event_schema_property p")
                .contains("INSERT INTO nx_event_schema_revision (id,current_revision) VALUES (1,302)")
                .doesNotContain("DELETE FROM nx_event_schema_property");
        assertThat(countOccurrences(startup, "20260729_l2_report_export_artifact_event_schema.sql"))
                .isEqualTo(1);
    }

    @Test
    void futureRegistryRevisionIsNotRegressedOrOverwrittenByRevision302Startup() throws Exception {
        String migration = normalizedMigration();

        assertThat(migration)
                .contains("owner_domain=IF(current_revision<=302,VALUES(owner_domain),owner_domain)")
                .contains("family_key=IF(current_revision<=302,VALUES(family_key),family_key)")
                .contains("producer=IF(current_revision<=302,VALUES(producer),producer)")
                .contains("consumers=IF(current_revision<=302,VALUES(consumers),consumers)")
                .contains("is_server_authoritative=IF(current_revision<=302,VALUES(is_server_authoritative),is_server_authoritative)")
                .contains("sampling_policy=IF(current_revision<=302,VALUES(sampling_policy),sampling_policy)")
                .contains("status=IF(current_revision<=302,VALUES(status),status)")
                .contains("updated_by=IF(current_revision<=302,VALUES(updated_by),updated_by)")
                .contains("reason=IF(current_revision<=302,VALUES(reason),reason)")
                .contains("is_deleted=IF(current_revision<=302,VALUES(is_deleted),is_deleted)")
                .contains("updated_at=IF(current_revision<=302,NOW(),updated_at)")
                .contains("current_revision=GREATEST(current_revision,302)");
    }

    private String normalizedMigration() throws Exception {
        return Files.readString(MIGRATION, StandardCharsets.UTF_8).replaceAll("\\s+", " ");
    }

    private int countOccurrences(String value, String needle) {
        return (value.length() - value.replace(needle, "").length()) / needle.length();
    }
}
