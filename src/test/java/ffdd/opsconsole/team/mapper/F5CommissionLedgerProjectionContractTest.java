package ffdd.opsconsole.team.mapper;

import static org.assertj.core.api.Assertions.assertThat;

import java.lang.reflect.Method;
import org.apache.ibatis.annotations.Select;
import org.junit.jupiter.api.Test;

class F5CommissionLedgerProjectionContractTest {
    @Test
    void legitimateIndependentLeaderboardRewardsAreKnownWithoutExpandingF5Scope() throws Exception {
        String sql = String.join("\n", F5CommissionMapper.class.getMethod("unknownCommissionKindCount")
                .getAnnotation(Select.class).value());
        assertThat(sql).contains("commission_type IS NULL", "LOWER(commission_type) NOT IN", "is_deleted=0",
                "'vrank_reward'", "'leaderboard_prize'");
        for (Method method : F5CommissionMapper.class.getMethods()) {
            if (java.util.Set.of("queryEvents", "countEvents", "queryExportEvents",
                    "aggregateCommissionKinds", "aggregateCommissionStatuses").contains(method.getName())) {
                String projection = String.join("\n", method.getAnnotation(Select.class).value());
                assertThat(projection).as(method.getName()).doesNotContain("'leaderboard_prize'", "'vrank_reward'");
            }
        }
    }

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
