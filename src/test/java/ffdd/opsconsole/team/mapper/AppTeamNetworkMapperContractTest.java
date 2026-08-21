package ffdd.opsconsole.team.mapper;

import static org.assertj.core.api.Assertions.assertThat;

import java.lang.reflect.Method;
import org.apache.ibatis.annotations.Select;
import org.junit.jupiter.api.Test;

class AppTeamNetworkMapperContractTest {
    @Test
    void binaryMembersConsumeCanonicalABLegAssignments() throws Exception {
        Method method = AppTeamNetworkMapper.class.getMethod("members", Long.class);
        String sql = String.join("\n", method.getAnnotation(Select.class).value());

        assertThat(sql)
                .contains("CASE UPPER(a.leg)")
                .contains("WHEN 'A' THEN 'A'")
                .contains("WHEN 'B' THEN 'B'")
                .contains("WHEN 'LEFT' THEN 'A'")
                .contains("WHEN 'RIGHT' THEN 'B'")
                .contains("a.member_user_id=n.root_user_id")
                .contains("LIMIT 501")
                .doesNotContain("LIMIT 500");
    }
}
