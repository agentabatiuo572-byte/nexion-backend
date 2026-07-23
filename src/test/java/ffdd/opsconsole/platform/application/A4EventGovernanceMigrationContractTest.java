package ffdd.opsconsole.platform.application;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

class A4EventGovernanceMigrationContractTest {
    private final String sql = readMigration();

    @Test
    void migrationCreatesDurableRegistryRevisionPropertiesAndDomainExtensions() {
        assertThat(sql).contains(
                "SET NAMES utf8mb4",
                "idx_event_outbox_analytics_time",
                "idx_event_outbox_family_time",
                "CREATE TABLE IF NOT EXISTS nx_event_schema_revision",
                "CREATE TABLE IF NOT EXISTS nx_event_schema_registry",
                "CREATE TABLE IF NOT EXISTS nx_event_schema_property",
                "CREATE TABLE IF NOT EXISTS nx_event_domain_extension");
    }

    @Test
    void migrationBackfillsCanonicalEnvelopeAndOnlyMarksRegisteredAnalyticsEvents() {
        assertThat(sql).contains(
                "JOIN nx_event_schema_registry s ON s.event_name=o.event_name",
                "o.payload=JSON_SET(",
                "'$.event_id'",
                "'$.event_name'",
                "'$.is_server_authoritative'",
                "o.schema_registered=1",
                "o.analytics_event=1");
    }

    @Test
    void migrationSeedsEveryLiveAnalyticsProducerBeforeRegistryGateIsEnabled() {
        assertThat(sql).contains(
                "admin.tamper_config_changed",
                "admin.killswitch_toggled",
                "admin.geo_policy_changed",
                "risk.tamper_detected",
                "risk.multi_account_flagged",
                "risk.multi_account_incident_created",
                "auth.register_completed",
                "checkout.completed",
                "checkout.started",
                "withdraw.submitted",
                "wallet.topup_confirmed",
                "'audience_role', 'enum'",
                "'affected_user_ids', 'json'");
    }

    @Test
    void migrationSeedsPrdDefaultsAndProtectedSamplingPolicy() {
        assertThat(sql).contains(
                "'admin.a4.event.kpi.day0', '90 秒'",
                "'admin.a4.event.kpi.retention', 'D1·D7·D30'",
                "'admin.a4.event.kpi.event_retention', '13 个月'",
                "资金/风控/转化 100%");
    }

    private static String readMigration() {
        try {
            return Files.readString(Path.of("scripts/migrations/20260717_a4_event_governance_closure.sql"));
        } catch (Exception ex) {
            throw new IllegalStateException(ex);
        }
    }
}
