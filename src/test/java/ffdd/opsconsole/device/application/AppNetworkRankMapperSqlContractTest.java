package ffdd.opsconsole.device.application;

import static org.assertj.core.api.Assertions.assertThat;

import ffdd.opsconsole.device.mapper.AppNetworkRankMapper;
import java.lang.reflect.Method;
import org.apache.ibatis.annotations.Select;
import org.junit.jupiter.api.Test;

class AppNetworkRankMapperSqlContractTest {
    @Test
    void rank_sql_is_environment_scoped_and_uses_active_owned_hashrate_only() throws Exception {
        Method method = AppNetworkRankMapper.class.getMethod("rankedUsers", Integer.class);
        String sql = String.join(" ", method.getAnnotation(Select.class).value()).toLowerCase();
        assertThat(sql).contains("nx_user_device", "sandbox=#{sandbox}", "ownership_status", "hashrate");
        assertThat(sql).contains("having coalesce(sum(d.hashrate),0) > 0");
    }
}
