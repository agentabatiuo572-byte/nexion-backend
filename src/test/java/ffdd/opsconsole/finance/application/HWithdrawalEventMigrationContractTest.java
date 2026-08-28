package ffdd.opsconsole.finance.application;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

class HWithdrawalEventMigrationContractTest {
    @Test
    void migrationRegistersTheExactServerAuthoritativeWithdrawalPayload() throws Exception {
        String sql = Files.readString(Path.of(
                "scripts/migrations/20260823_withdrawal_submitted_schema_alignment.sql"));

        assertThat(sql)
                .contains("withdraw.submitted")
                .contains("SELECT 'withdrawal_id' property_name,'id' property_type")
                .contains("'amount_usdt','number'")
                .contains("'chain','enum'")
                .contains("'network_confirm_usd','number'")
                .contains("'policy_version','string'")
                .contains("'use_nex_fee_offset','boolean'")
                .contains("'penalty_fee_rate','number'")
                .contains("'gross_fee','number'")
                .contains("'nex_burned','number'")
                .contains("'fee_waived','number'")
                .contains("'actual_fee','number'")
                .contains("'net_receive','number'")
                .contains("'cooldown_days','number'")
                .contains("'hold_until','timestamp'")
                .contains("'small_amount_auto_review','boolean'")
                .contains("'small_amount_threshold_usd','number'")
                .contains("'payout_sla_hours','number'")
                .contains("'payout_due_at','timestamp'")
                .contains("'strong_review','boolean'")
                .contains("'strong_review_threshold_usdt','number'")
                .contains("current_revision=309")
                .contains("registry_revision=309")
                .doesNotContain("'network_fee_rate'")
                .doesNotContain("'network_fee_min'")
                .doesNotContain("'network_fee_max'");
    }

    @Test
    void dueLifecycleMigrationAllowsUnavailableRiskWithoutDroppingLifecycleContext() throws Exception {
        String sql = Files.readString(Path.of(
                "scripts/migrations/20260823_d2_lifecycle_event_schema_alignment.sql"));

        assertThat(sql)
                .contains("event_name='withdraw.review_due'")
                .contains("current_revision=310")
                .contains("property_name='risk_score'")
                .contains("required_field=0")
                .contains("'risk_score_status','string',0,0,310")
                .contains("registry_revision=310");
    }
}
