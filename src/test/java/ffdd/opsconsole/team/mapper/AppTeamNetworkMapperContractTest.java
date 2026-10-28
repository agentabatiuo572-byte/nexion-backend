package ffdd.opsconsole.team.mapper;

import static org.assertj.core.api.Assertions.assertThat;

import java.lang.reflect.Method;
import org.apache.ibatis.annotations.Select;
import org.junit.jupiter.api.Test;

class AppTeamNetworkMapperContractTest {
    @Test
    void nameProjectionReturnsDatabaseNicknameAndKeepsNetworkScope() throws Exception {
        String sql = String.join("\n", AppTeamNetworkMapper.class
                .getMethod("membersPage", Long.class, long.class, int.class).getAnnotation(Select.class).value());
        assertThat(sql).contains("u.nickname nickname",
                "child.sandbox=owner.sandbox", "child.sandbox=parent.sandbox",
                "owner.status='ACTIVE' AND owner.is_deleted=0",
                "child.status='ACTIVE' AND child.is_deleted=0");
        assertThat(sql).doesNotContain("NexGrid", "CONCAT('UVEL");
    }

    @Test
    void binaryMembersConsumeCanonicalABLegAssignments() throws Exception {
        Method method = AppTeamNetworkMapper.class.getMethod("membersPage", Long.class, long.class, int.class);
        String sql = String.join("\n", method.getAnnotation(Select.class).value());

        assertThat(sql)
                .contains("CASE UPPER(a.leg)")
                .contains("WHEN 'A' THEN 'A'")
                .contains("WHEN 'B' THEN 'B'")
                .contains("WHEN 'LEFT' THEN 'A'")
                .contains("WHEN 'RIGHT' THEN 'B'")
                .contains("a.member_user_id=n.root_user_id")
                .contains("WHERE u.id>#{afterId}")
                .contains("ORDER BY u.id")
                .contains("LIMIT #{limit}");
    }
}
