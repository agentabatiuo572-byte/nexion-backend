package ffdd.opsconsole.finance.mapper;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

class AppVietQrIntentMigrationContractTest {

    @Test
    void migrationEnforcesCanonicalIdsMemoIdempotencyAndStateDomain() throws Exception {
        String sql = Files.readString(Path.of(
                "scripts", "migrations", "20260725_vietqr_intent_app.sql"));

        assertThat(sql)
                .contains("UNIQUE KEY uk_vietqr_intent_no (intent_no)")
                .contains("UNIQUE KEY uk_vietqr_intent_user_create_key (user_id, create_idempotency_key)")
                .contains("UNIQUE KEY uk_vietqr_intent_memo (memo_code)")
                .contains("status IN (")
                .contains("'AWAITING_PAYMENT','RECEIPT_REVIEW','CREDITED','EXPIRED','MISMATCH_REVIEW'")
                .contains("'LATE_REVIEW','CANCELLED','RETURN_PENDING','RETURNED'")
                .contains("received_business_date")
                .contains("DATE(DATE_ADD(UTC_TIMESTAMP(), INTERVAL 7 HOUR))")
                .contains("intent_transition_required")
                .contains("information_schema.columns")
                .contains("version BIGINT NOT NULL DEFAULT 0")
                .doesNotContain("CURRENT_DATE")
                .doesNotContain("INSERT INTO nx_vietqr_intent");
    }
}
