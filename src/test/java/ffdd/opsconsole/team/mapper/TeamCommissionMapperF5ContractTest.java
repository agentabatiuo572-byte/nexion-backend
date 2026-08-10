package ffdd.opsconsole.team.mapper;

import static org.assertj.core.api.Assertions.assertThat;

import java.lang.reflect.Method;
import org.apache.ibatis.annotations.Select;
import org.junit.jupiter.api.Test;

class TeamCommissionMapperF5ContractTest {

    @Test
    void explicitFrozenStatusWinsOverElapsedUnlockTime() throws Exception {
        Method method = TeamCommissionMapper.class.getMethod("commissionEvents", int.class);
        String sql = String.join(" ", method.getAnnotation(Select.class).value())
                .replaceAll("\\s+", " ")
                .toUpperCase();

        int firstFrozen = sql.indexOf("WHEN UPPER(STATUS) = 'FROZEN'");
        int firstElapsed = sql.indexOf("UNLOCK_AT IS NOT NULL AND UNLOCK_AT <= NOW()");
        assertThat(firstFrozen).isGreaterThanOrEqualTo(0);
        assertThat(firstElapsed).isGreaterThan(firstFrozen);
        assertThat(sql).contains("UPPER(STATUS) IN ('COOLING', 'PENDING') AND UNLOCK_AT IS NOT NULL");
    }

    @Test
    void fullKindAggregateOrdersByGroupedAliasesUnderOnlyFullGroupBy() throws Exception {
        Method method = F5CommissionMapper.class.getMethod("aggregateCommissionKinds");
        String sql = String.join(" ", method.getAnnotation(Select.class).value())
                .replaceAll("\\s+", " ")
                .toUpperCase();

        assertThat(sql).contains("GROUP BY LOWER(E.COMMISSION_TYPE), UPPER(E.CURRENCY)")
                .contains("ORDER BY FIELD(KIND,")
                .contains(", CURRENCY");
        assertThat(sql).doesNotContain("ORDER BY FIELD(LOWER(E.COMMISSION_TYPE)");
    }


    @Test
    void fullStatusAggregateIncludesWithdrawnAndFrozenWithoutASampleLimit() throws Exception {
        Method method = F5CommissionMapper.class.getMethod("aggregateCommissionStatuses");
        String sql = String.join(" ", method.getAnnotation(Select.class).value())
                .replaceAll("\\s+", " ")
                .toUpperCase();

        assertThat(sql)
                .contains("'WITHDRAWN'")
                .contains("'FROZEN'")
                .contains("GROUP BY NORMALIZED.STATUS, NORMALIZED.CURRENCY")
                .doesNotContain("LIMIT 200");
    }

    @Test
    void unknownRawStatusesAreNeverRelabeledAsUnlockedOrCooling() throws Exception {
        Method aggregate = F5CommissionMapper.class.getMethod("aggregateCommissionStatuses");
        String aggregateSql = String.join(" ", aggregate.getAnnotation(Select.class).value())
                .replaceAll("\\s+", " ")
                .toUpperCase();
        Method query = F5CommissionMapper.class.getMethod(
                "queryEvents", String.class, String.class, Long.class, String.class,
                String.class, Long.class, int.class);
        String querySql = String.join(" ", query.getAnnotation(Select.class).value())
                .replaceAll("\\s+", " ")
                .toUpperCase();

        assertThat(aggregateSql).contains("ELSE 'UNKNOWN'")
                .doesNotContain("'BLOCKED' THEN 'UNLOCKED'", "'FAILED' THEN 'COOLING'");
        assertThat(querySql).contains("ELSE 'UNKNOWN'")
                .doesNotContain("'BLOCKED' THEN 'UNLOCKED'", "'FAILED' THEN 'COOLING'");
    }

    @Test
    void knownF1VrankRewardIsExcludedFromF5WithoutBeingTreatedAsUnknown() throws Exception {
        Method method = F5CommissionMapper.class.getMethod("unknownCommissionKindCount");
        String sql = String.join(" ", method.getAnnotation(Select.class).value())
                .replaceAll("\\s+", " ")
                .toUpperCase();

        assertThat(sql).contains("'VRANK_REWARD'")
                .contains("'NETWORK'", "'BINARY'", "'PEER'", "'CULTIVATION'", "'LEADERSHIP'", "'GENESIS'");
    }
}
