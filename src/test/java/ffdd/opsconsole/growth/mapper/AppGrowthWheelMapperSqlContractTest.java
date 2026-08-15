package ffdd.opsconsole.growth.mapper;

import static org.assertj.core.api.Assertions.assertThat;

import java.lang.reflect.Method;
import org.apache.ibatis.annotations.Select;
import org.junit.jupiter.api.Test;

class AppGrowthWheelMapperSqlContractTest {

    @Test
    void activeTierProjectionKeepsEveryRecordConstructorColumnInOrder() throws Exception {
        Method method = AppGrowthWheelMapper.class.getMethod("listActiveTiers");
        String sql = String.join(" ", method.getAnnotation(Select.class).value())
                .replaceAll("\\s+", " ")
                .toLowerCase();

        assertThat(sql).containsSubsequence(
                "id tierid",
                "tier_name tiername",
                "reward_name rewardname",
                "probability_pct probabilitypct",
                "real_outflow realoutflow",
                "lower(reward_kind) rewardkind",
                "reward_amount rewardamount",
                "voucher_id voucherid",
                "daily_stock dailystock");
    }
}
