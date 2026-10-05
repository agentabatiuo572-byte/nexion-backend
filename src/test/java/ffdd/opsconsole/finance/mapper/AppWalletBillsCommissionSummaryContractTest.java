package ffdd.opsconsole.finance.mapper;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDateTime;
import org.apache.ibatis.annotations.Select;
import org.junit.jupiter.api.Test;

class AppWalletBillsCommissionSummaryContractTest {
    @Test
    void onlyCancelledCommissionAccrualsAreExcludedFromTheThreeGrossRewardProjections() throws Exception {
        String sql = String.join("\n", AppWalletBillsMapper.class.getMethod("summary", Long.class,
                LocalDateTime.class, LocalDateTime.class, LocalDateTime.class, LocalDateTime.class)
                .getAnnotation(Select.class).value());
        String rewardProjections = sql.substring(0, sql.indexOf("todayNexEarn"));
        assertThat(rewardProjections.split("AND NOT", -1)).hasSize(4);
        assertThat(rewardProjections).contains("UPPER(biz_type)='TEAM_COMMISSION' AND UPPER(status)='CANCELLED'");
        assertThat(sql).contains("WHERE user_id=#{userId} AND is_deleted=0");
    }
}
