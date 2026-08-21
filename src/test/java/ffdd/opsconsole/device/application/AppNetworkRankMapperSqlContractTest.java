package ffdd.opsconsole.device.application;

import static org.assertj.core.api.Assertions.assertThat;

import ffdd.opsconsole.device.mapper.AppNetworkRankMapper;
import java.lang.reflect.Method;
import org.apache.ibatis.annotations.Select;
import org.junit.jupiter.api.Test;

class AppNetworkRankMapperSqlContractTest {
    @Test
    void rank_sql_is_environment_scoped_and_uses_active_owned_hashrate_only() throws Exception {
        Method method = AppNetworkRankMapper.class.getMethod("rankedUsers");
        String sql = String.join(" ", method.getAnnotation(Select.class).value()).toLowerCase();
        assertThat(sql).contains("nx_user_device", "sandbox=0", "ownership_status", "hashrate")
                .doesNotContain("sandbox=#{sandbox}");
        assertThat(sql).contains("having coalesce(sum(d.hashrate),0) > 0");
    }

    @Test
    void sandbox_rank_query_is_strictly_run_scoped() throws Exception {
        Method method = AppNetworkRankMapper.class.getMethod("rankedSandboxUsers", String.class);
        Select select = method.getAnnotation(Select.class);
        assertThat(select).isNotNull();
        String sql = String.join(" ", select.value()).replaceAll("\\s+", " ").toLowerCase();
        assertThat(sql).contains("upper(d.source_environment)='sandbox'")
                .contains("run_id=#{runid}")
                .contains("u.sandbox=1")
                .doesNotContain("u.sandbox=0");
    }
}
