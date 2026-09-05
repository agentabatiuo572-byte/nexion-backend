package ffdd.opsconsole.team.mapper;

import org.junit.jupiter.api.Test;
import org.apache.ibatis.annotations.Select;
import static org.assertj.core.api.Assertions.assertThat;

class QuotaMonthContractTest {
    @Test
    void appPcSummaryAndPcDetailsUseTheSameHalfOpenUtcMonth() throws Exception {
        for (var method : new java.lang.reflect.Method[] {
                AppTeamQuotaMapper.class.getMethod("quotaRows"),
                TeamCommissionMapper.class.getMethod("quotaRows"),
                TeamCommissionMapper.class.getMethod("quotaUsages", int.class) }) {
            String sql = String.join(" ", method.getAnnotation(Select.class).value());
            assertThat(sql).contains("u.occurred_at >= DATE_FORMAT(UTC_TIMESTAMP(), '%Y-%m-01')")
                    .contains("u.occurred_at < DATE_ADD(DATE_FORMAT(UTC_TIMESTAMP(), '%Y-%m-01'), INTERVAL 1 MONTH)");
        }
    }
}
