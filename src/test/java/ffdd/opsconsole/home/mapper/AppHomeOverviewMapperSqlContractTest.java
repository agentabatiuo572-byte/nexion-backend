package ffdd.opsconsole.home.mapper;

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.reflect.Method;
import org.apache.ibatis.annotations.Select;
import org.junit.jupiter.api.Test;

class AppHomeOverviewMapperSqlContractTest {
    @Test
    void earningsRankingOrdersByDailyYieldBeforeApplyingTheLimit() throws Exception {
        String sql = select("marketProducts");
        assertTrue(sql.contains("ORDER BY p.estimated_daily_usdt DESC"));
    }
    @Test
    void accountFactsCarryUserAndEnvironmentBoundaries() throws Exception {
        String earnings = select("earnings");
        String grid = select("onGrid");
        String devices = select("onGridClients");
        assertTrue(earnings.contains("user_id = #{userId}"));
        assertTrue(earnings.contains("source_environment"));
        assertTrue(grid.contains("source_environment"));
        assertTrue(grid.contains("u.sandbox = #{sandbox}"));
        assertTrue(devices.contains("u.sandbox = #{sandbox}"));
        assertTrue(devices.contains("SHA2("));
        assertTrue(devices.contains("current_client.client_name"));
        assertTrue(devices.contains("t.user_device_id"));
        assertTrue(devices.contains("current_client.id = ("));
        assertTrue(devices.contains("t.user_device_id = d.id"));
        assertTrue(devices.contains("ORDER BY t.user_device_id, t.is_deleted, t.client_observed_at DESC, t.id DESC"));
        assertTrue(devices.contains("COALESCE(current_client.client_name, dc.display_name) AS name"));
        assertTrue(devices.contains("COALESCE(dc.location, NULLIF(d.dc_location, '')) AS city"));
        assertTrue(!devices.contains("u.email"));
        assertTrue(!devices.contains("u.phone"));
        assertTrue(!devices.contains("Pocket Studios"));
        assertTrue(!devices.contains("Helix Labs"));
        assertTrue(!devices.contains("Echo Earbuds"));
    }

    @Test
    void sandboxHomeUsesOnlyRunScopedDevicesAndNeverConsumesLegacyRewardRows() throws Exception {
        String devices = select("sandboxActiveDevices");
        assertTrue(devices.contains("d.user_id = #{userId}"));
        assertTrue(devices.contains("d.source_environment = 'SANDBOX'"));
        assertTrue(devices.contains("d.run_id = #{runId}"));
        assertTrue(java.util.Arrays.stream(AppHomeOverviewMapper.class.getDeclaredMethods())
                .noneMatch(method -> method.getName().equals("sandboxEarnings")
                        || method.getName().equals("sandboxEarningsLedger")));
    }

    private String select(String name) throws Exception {
        Method method = java.util.Arrays.stream(AppHomeOverviewMapper.class.getDeclaredMethods())
                .filter(candidate -> candidate.getName().equals(name)).findFirst().orElseThrow();
        return method.getAnnotation(Select.class).value()[0];
    }
}
