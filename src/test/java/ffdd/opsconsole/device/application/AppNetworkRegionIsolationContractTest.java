package ffdd.opsconsole.device.application;

import static org.assertj.core.api.Assertions.assertThat;

import ffdd.opsconsole.device.mapper.AppNetworkRegionMapper;
import org.apache.ibatis.annotations.Select;
import org.junit.jupiter.api.Test;

class AppNetworkRegionIsolationContractTest {
    @Test
    void everyGlobalProjectionIsProductionOnlyUntilRunScopedDeviceProjectionExists() throws Exception {
        Select select = AppNetworkRegionMapper.class.getMethod("regions", Long.class).getAnnotation(Select.class);
        String sql = String.join(" ", select.value()).replaceAll("\\s+", " ");
        assertThat(sql).contains("owner.sandbox = 0")
                .contains("viewer.sandbox = 0")
                .contains("viewer.id = #{userId}")
                .contains("COALESCE(t.source_environment, 'PRODUCTION') = 'PRODUCTION'")
                .doesNotContain("viewer.sandbox = 1")
                .doesNotContain("= 'SANDBOX'");
    }
}
