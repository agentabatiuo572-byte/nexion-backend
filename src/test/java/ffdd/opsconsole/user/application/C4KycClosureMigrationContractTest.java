package ffdd.opsconsole.user.application;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

class C4KycClosureMigrationContractTest {
    @Test
    void c4LedgerRbacEventsAndCrossDomainContractsAreDurablySeeded() throws Exception {
        String migration = Files.readString(
                Path.of("scripts/migrations/20260719_c4_kyc_closure.sql"),
                StandardCharsets.UTF_8).replaceAll("\\s+", " ");

        assertThat(migration)
                .contains("nx_kyc_status_history")
                .contains("paired_address")
                .contains("trigger_source")
                .contains("user_c4_verify")
                .contains("user_c4_revoke")
                .contains("user_c4_trigger_review")
                .contains("user_c4_export")
                .contains("user_c4_network_write")
                .contains("admin.kyc_status_changed")
                .contains("risk.kyc_review_triggered")
                .contains("admin.kyc_export_created")
                .contains("kyc.network_whitelist")
                .contains("TRIGGER trg_nx_user_kyc_profile")
                .contains("'REGISTRATION'")
                .contains("report_type='KYC_REGULATORY'")
                .contains("SELECT 'evidence_ref','string',0")
                .doesNotContain("SELECT 'evidence_ref','text',0");

        String repair = Files.readString(
                Path.of("scripts/migrations/20260720_c4_event_schema_type_repair.sql"),
                StandardCharsets.UTF_8).replaceAll("\\s+", " ");
        assertThat(repair)
                .contains("admin.kyc_status_changed")
                .contains("risk.kyc_review_triggered")
                .contains("admin.kyc_export_created")
                .contains("p.property_name = 'evidence_ref'")
                .contains("p.property_type = 'text'")
                .contains("p.property_type = 'string'");
    }

    @Test
    void kycNumberKeepsLegacyEightDigitFormatAndDoesNotCollapseLargeUserIds() throws Exception {
        assertThat(kycNo(42L)).isEqualTo("KYC-00000042");
        assertThat(kycNo(60_722_152_714L)).isEqualTo("KYC-60722152714");
        assertThat(kycNo(60_722_152_715L)).isNotEqualTo(kycNo(60_722_152_714L));

        String schema = compact("scripts/schema.sql");
        String closure = compact("scripts/migrations/20260719_c4_kyc_closure.sql");
        String repair = compact("scripts/migrations/20260722_c4_kyc_number_overflow_repair.sql");
        for (String definition : java.util.List.of(schema, closure, repair)) {
            assertThat(definition)
                    .contains("IF(NEW.id < 100000000,LPAD(NEW.id,8,'0'),CAST(NEW.id AS CHAR))")
                    .doesNotContain("CONCAT('KYC-',LPAD(NEW.id,8,'0'))");
        }
        assertThat(repair)
                .contains("DROP TRIGGER IF EXISTS trg_nx_user_kyc_profile")
                .contains("CREATE TRIGGER trg_nx_user_kyc_profile")
                .doesNotContain("UPDATE nx_kyc_profile")
                .doesNotContain("DELETE FROM nx_kyc_profile");
    }

    private String compact(String source) throws Exception {
        return Files.readString(Path.of(source), StandardCharsets.UTF_8).replaceAll("\\s+", " ");
    }

    private String kycNo(long userId) {
        String suffix = userId < 100_000_000L ? "%08d".formatted(userId) : Long.toString(userId);
        return "KYC-" + suffix;
    }
}
