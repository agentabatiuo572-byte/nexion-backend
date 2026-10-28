package ffdd.opsconsole.team.mapper;

import org.junit.jupiter.api.Test;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;
import ffdd.opsconsole.shared.config.HardwareQuotaPeriod;
import ffdd.opsconsole.shared.canonical.mapper.CanonicalStateMapper;
import static org.assertj.core.api.Assertions.assertThat;

class QuotaMonthContractTest {
    @Test
    void appPcSummaryAndPcDetailsUseTheSameVersionedUtcStorageBounds() throws Exception {
        for (var method : new java.lang.reflect.Method[] {
                AppTeamQuotaMapper.class.getMethod("quotaRows"),
                TeamCommissionMapper.class.getMethod("quotaRows"),
                TeamCommissionMapper.class.getMethod("quotaUsages", int.class),
                CanonicalStateMapper.class.getMethod("purchaseHardwareQuotas", String.class) }) {
            String sql = String.join(" ", method.getAnnotation(Select.class).value());
            assertThat(sql.replace(" ", "")).contains(("u.occurred_at>=" + HardwareQuotaPeriod.SQL_MONTH_START_UTC).replace(" ", ""))
                    .contains(("u.occurred_at<" + HardwareQuotaPeriod.SQL_NEXT_MONTH_START_UTC).replace(" ", ""));
        }
        String cas = TeamCommissionMapper.class.getMethod("updateHardwareQuotaTierCas", String.class, int.class, int.class)
                .getAnnotation(Update.class).value()[0];
        assertThat(cas).contains(HardwareQuotaPeriod.SQL_MONTH_START_UTC, HardwareQuotaPeriod.SQL_NEXT_MONTH_START_UTC);
        String volume = CanonicalStateMapper.class.getMethod("purchaseQuotaMonthlyComputePoints", Long.class)
                .getAnnotation(Select.class).value()[0];
        assertThat(volume).contains(HardwareQuotaPeriod.SQL_MONTH_START_ORDER_STORAGE, HardwareQuotaPeriod.SQL_NEXT_MONTH_START_ORDER_STORAGE);
    }
}
