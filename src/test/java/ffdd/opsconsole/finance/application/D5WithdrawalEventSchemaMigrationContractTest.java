package ffdd.opsconsole.finance.application;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

class D5WithdrawalEventSchemaMigrationContractTest {
    @Test
    void migrationRegistersTheExactCurrentWithdrawSubmittedPayloadAndD2NetworkFeeColumns() throws Exception {
        String sql = Files.readString(
                Path.of("scripts/migrations/20260727_d5_withdrawal_execution_closure.sql"),
                StandardCharsets.UTF_8);

        for (String property : java.util.List.of(
                "withdrawal_id", "amount_usdt", "chain",
                "network_fee_rate", "network_fee_min", "network_fee_max", "network_fee",
                "penalty_fee_rate", "penalty_fee", "gross_fee", "nex_burned",
                "fee_waived", "penalty_fee_waived", "network_fee_waived",
                "actual_penalty_fee", "actual_network_fee",
                "actual_fee", "net_receive", "cooldown_days", "hold_until",
                "risk_route", "k3_risk_route", "risk_rule_id", "k4_priority",
                "k4_risk_score", "k4_model_version", "k4_as_of")) {
            assertThat(sql).contains("'" + property + "'");
        }
        assertThat(sql)
                .contains("d2_network_fee_rate")
                .contains("d2_network_fee_min")
                .contains("d2_network_fee_max")
                .contains("d2_network_fee")
                .contains("p.property_name NOT IN")
                .contains("current_revision=276");
    }
}
