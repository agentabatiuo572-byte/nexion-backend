package ffdd.opsconsole.treasury.mapper;

import static org.assertj.core.api.Assertions.assertThat;

import java.lang.reflect.Method;
import org.apache.ibatis.annotations.Select;
import org.junit.jupiter.api.Test;

class TreasuryLedgerMapperSqlContractTest {
    @Test
    void reconciliationGapIncludesWalletOnlyAndLedgerOnlyUsers() throws Exception {
        Method method = TreasuryLedgerMapper.class.getMethod("walletLedgerReconciliationGapUsdt");
        String sql = String.join("\n", method.getAnnotation(Select.class).value());

        assertThat(sql)
                .contains("UNION")
                .contains("FROM nx_user_wallet")
                .contains("FROM nx_wallet_ledger")
                .contains("COALESCE(w.usdt_available, 0)")
                .contains("COALESCE(latest.balance_after, 0)")
                .doesNotContain("'PENDING'");
    }
}
