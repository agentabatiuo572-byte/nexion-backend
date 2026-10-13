package ffdd.opsconsole.platform.mapper;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Map;
import org.apache.ibatis.session.Configuration;
import org.junit.jupiter.api.Test;

class AdminAccountStateLoginMapperTest {
    @Test
    void loginStatementBindsIdentityUsesDatabaseClockAndOnlyMaintainsLoginTimestamp() {
        Configuration configuration = new Configuration();
        configuration.addMapper(AdminAccountStateMapper.class);
        var statement = configuration.getMappedStatement(AdminAccountStateMapper.class.getName() + ".recordAuthenticatedLogin");
        var bound = statement.getBoundSql(Map.of("adminId", 42L));
        String sql = bound.getSql().replaceAll("\\s+", " ").trim();

        assertThat(bound.getParameterMappings()).extracting(mapping -> mapping.getProperty()).containsExactly("adminId");
        assertThat(sql).contains("INSERT INTO nx_admin_account_state (admin_id, last_login_at)", "VALUES (?, NOW())");
        assertThat(sql.substring(sql.indexOf("ON DUPLICATE KEY UPDATE")))
                .isEqualTo("ON DUPLICATE KEY UPDATE last_login_at = GREATEST( COALESCE(last_login_at, VALUES(last_login_at)), VALUES(last_login_at))");
    }
}
