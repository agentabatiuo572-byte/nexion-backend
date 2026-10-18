package ffdd.opsconsole.finance.mapper;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDateTime;
import org.apache.ibatis.annotations.Select;
import org.junit.jupiter.api.Test;

class AppWalletBillsCommissionSummaryContractTest {
    @Test
    void canonicalComputeRewardFastPathIsBinaryExactAndKeepsTheMaterializedClassificationOrder() throws Exception {
        String sql = String.join("\n", AppWalletBillsMapper.class.getMethod("summary", Long.class,
                LocalDateTime.class, LocalDateTime.class, LocalDateTime.class, LocalDateTime.class)
                .getAnnotation(Select.class).value());
        String fastPath = "WHEN CAST(UPPER(COALESCE(biz_type,'')) AS BINARY)"
                + "=CAST('COMPUTE_TASK_REWARD' AS BINARY) THEN 'reward'";
        assertThat(sql).contains("NO_MERGE(scoped)", fastPath);
        assertThat(sql.indexOf(fastPath)).isGreaterThan(sql.indexOf("IN ('TRIAL_BONUS','DAILY_CHECK_IN')"))
                .isLessThan(sql.indexOf("REGEXP 'DEPOSIT|TOPUP|RECHARGE'"));
        assertThat(sql).contains("REGEXP 'REWARD|BONUS'", "REGEXP 'EARN|RELEASE|TASK|TRIAL'",
                "WHERE user_id=#{userId} AND is_deleted=0");
    }

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
