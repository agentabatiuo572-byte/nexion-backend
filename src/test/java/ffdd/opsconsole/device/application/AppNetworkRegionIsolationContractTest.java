package ffdd.opsconsole.device.application;

import static org.assertj.core.api.Assertions.assertThat;

import ffdd.opsconsole.device.mapper.AppNetworkRegionMapper;
import org.apache.ibatis.annotations.Select;
import org.junit.jupiter.api.Test;

class AppNetworkRegionIsolationContractTest {
    @Test
    void everyGlobalProjectionIsBoundToTheAuthenticatedUsersEnvironment() throws Exception {
        Select select = AppNetworkRegionMapper.class.getMethod("regions", Long.class).getAnnotation(Select.class);
        String sql = String.join(" ", select.value()).replaceAll("\\s+", " ");
        assertThat(sql).contains("owner.sandbox = viewer.sandbox")
                .contains("viewer.id = #{userId}")
                .contains("viewer.sandbox = 1")
                .contains("source_environment, 'PRODUCTION') = 'SANDBOX'");
    }
}
