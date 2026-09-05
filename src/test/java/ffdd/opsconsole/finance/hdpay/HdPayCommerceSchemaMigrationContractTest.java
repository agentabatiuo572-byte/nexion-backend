package ffdd.opsconsole.finance.hdpay;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

class HdPayCommerceSchemaMigrationContractTest {
    private static final String MIGRATION =
            "scripts/migrations/20260903_hdpay_commerce_direct_purchase.sql";

    @Test
    void migrationBindsExactlyOneCommerceOrderToAnExplicitSettlementTarget() throws Exception {
        String sql = Files.readString(Path.of(MIGRATION));

        assertThat(sql)
                .contains("settlement_target_type VARCHAR(24) NOT NULL DEFAULT ''WALLET_TOPUP''")
                .contains("target_order_no VARCHAR(96) NULL")
                .contains("UNIQUE KEY uk_vietqr_intent_target_order (target_order_no)")
                .contains("CONSTRAINT chk_vietqr_intent_settlement_target CHECK")
                .contains("settlement_target_type=''COMMERCE_ORDER'' AND target_order_no IS NOT NULL")
                .contains("DROP CHECK chk_vietqr_intent_requested")
                .contains("settlement_target_type=''WALLET_TOPUP'' AND requested_usdt >= 10")
                .contains("settlement_target_type=''COMMERCE_ORDER'' AND requested_usdt > 0");
    }

    @Test
    void startupRunnerIncludesCommerceBindingAfterCallbackSettlement() throws Exception {
        String runner = Files.readString(Path.of("scripts/apply_startup_schema_migrations.ps1"));

        assertThat(runner.indexOf("20260903_hdpay_commerce_direct_purchase.sql"))
                .isGreaterThan(runner.indexOf("20260902_hdpay_callback_settlement.sql"));
        assertThat(runner.indexOf("20260904_hdpay_submission_unknown_before_network.sql"))
                .isGreaterThan(runner.indexOf("20260903_hdpay_commerce_direct_purchase.sql"));
    }

    @Test
    void legacyPendingRowsBecomeQueryOnlyUnknownsInsteadOfBeingResubmitted() throws Exception {
        String sql = Files.readString(Path.of(
                "scripts/migrations/20260904_hdpay_submission_unknown_before_network.sql"));

        assertThat(sql)
                .contains("SET submission_status='SUBMIT_UNKNOWN'")
                .contains("last_error_code='HDPAY_LEGACY_PENDING_RECOVERY'")
                .contains("WHERE submission_status='PENDING'");
    }
}
