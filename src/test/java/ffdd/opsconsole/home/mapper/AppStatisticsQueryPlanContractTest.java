package ffdd.opsconsole.home.mapper;

import static org.assertj.core.api.Assertions.assertThat;

import ffdd.opsconsole.shared.canonical.mapper.CanonicalStateMapper;
import java.util.Arrays;
import org.apache.ibatis.annotations.Select;
import org.junit.jupiter.api.Test;

class AppStatisticsQueryPlanContractTest {
    @Test
    void gridFiltersUseTheExistingStatusIndexWithoutWrappingNonNullableColumns() {
        String sql = sql(AppHomeOverviewMapper.class, "onGrid");
        assertThat(sql).contains("t.status IN", "t.source_environment = #{sourceEnvironment}")
                .doesNotContain("UPPER(t.status)", "COALESCE(t.source_environment");
    }

    @Test
    void allFiveEarningsWindowsShareOneReceiptScanAndOneCutoff() {
        String sql = sql(AppHomeOverviewMapper.class, "earningsSummary");
        assertThat(sql).contains("#{dayStart}", "#{yesterdayStart}", "#{yesterdayEnd}",
                        "#{weekStart}", "#{monthStart}", "r.completed_at < #{endAt}",
                        "r.user_id = #{userId}", "r.source_environment = #{sourceEnvironment}")
                .doesNotContain("UNION", "UPPER(r.earning_status)", "COALESCE(r.source_environment");
        assertThat(sql.split("FROM nx_compute_receipt", -1)).hasSize(2);
    }

    @Test
    void deviceLifetimeEarningsAreAggregatedOnlyForTheRequestedOwnersDevices() {
        String sql = sql(CanonicalStateMapper.class, "ownedDevices");
        assertThat(sql).contains("GROUP BY r.user_device_id", "owned.user_id = #{userId}")
                .doesNotContain("(SELECT SUM(r.reward_usdt)", "UPPER(r.earning_status)",
                        "COALESCE(r.source_environment");
    }

    @Test
    void latestClientLookupDoesNotRankTheEntireTaskHistory() {
        String sql = sql(AppHomeOverviewMapper.class, "onGridClients");
        assertThat(sql).doesNotContain("ROW_NUMBER()", "PARTITION BY")
                .contains("t.user_device_id = d.id", "LIMIT 1",
                        "ORDER BY t.user_device_id, t.is_deleted, t.client_observed_at DESC, t.id DESC");
    }

    private String sql(Class<?> mapper, String name) {
        return Arrays.stream(mapper.getDeclaredMethods()).filter(method -> method.getName().equals(name))
                .findFirst().orElseThrow().getAnnotation(Select.class).value()[0];
    }
}
