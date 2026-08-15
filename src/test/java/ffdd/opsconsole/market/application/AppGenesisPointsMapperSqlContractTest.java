package ffdd.opsconsole.market.application;

import static org.assertj.core.api.Assertions.assertThat;

import ffdd.opsconsole.market.mapper.AppGenesisPointsMapper;
import java.lang.reflect.Method;
import org.apache.ibatis.annotations.Select;
import org.junit.jupiter.api.Test;

class AppGenesisPointsMapperSqlContractTest {
    @Test
    void leaderboard_sql_must_join_user_environment_and_never_mix_sandbox_rows() throws Exception {
        Method method = AppGenesisPointsMapper.class.getMethod("leaderboard", Integer.class);
        String sql = String.join(" ", method.getAnnotation(Select.class).value()).toLowerCase();
        assertThat(sql).contains("nx_genesis_holding", "nx_user", "sandbox=#{sandbox}");
        assertThat(sql).contains("count(h.id)");
    }
}
