package ffdd.opsconsole.finance.application;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

class HWithdrawalEventMigrationContractTest {
    @Test
    void migrationRegistersTheExactServerAuthoritativeWithdrawalPayload() throws Exception {
        String sql = Files.readString(Path.of(
                "scripts/migrations/20260722_h_d5_withdraw_event.sql"));

        assertThat(sql)
                .contains("withdraw.submitted")
                .contains("SELECT 'withdrawal_id' property_name,'id' property_type")
                .contains("'amount_usdt','number'")
                .contains("'chain','enum'")
                .contains("'penalty_fee_rate','number'")
                .contains("'gross_fee','number'")
                .contains("'nex_burned','number'")
                .contains("'fee_waived','number'")
                .contains("'actual_fee','number'")
                .contains("'net_receive','number'")
                .contains("'cooldown_days','number'")
                .contains("'hold_until','timestamp'")
                .contains("VALUES (1,108)");
    }
}
