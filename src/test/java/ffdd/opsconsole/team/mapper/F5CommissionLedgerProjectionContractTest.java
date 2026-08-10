package ffdd.opsconsole.team.mapper;

import static org.assertj.core.api.Assertions.assertThat;

import java.lang.reflect.Method;
import org.apache.ibatis.annotations.Select;
import org.junit.jupiter.api.Test;

class F5CommissionLedgerProjectionContractTest {
    @Test
    void canonicalEventQueryProjectsTheAuthoritativeLedgerBusinessNumber() throws Exception {
        Method query = F5CommissionMapper.class.getMethod(
                "queryEvents", String.class, String.class, Long.class, String.class,
                String.class, Long.class, int.class);
        String sql = String.join("\n", query.getAnnotation(Select.class).value());

        assertThat(sql).containsIgnoringCase("AS ledgerBizNo");
        assertThat(sql).contains("nx_wallet_ledger");
        assertThat(sql).doesNotContain("CONCAT('CM-', e.id) AS ledgerBizNo");
    }
}
