package ffdd.opsconsole.platform.mapper;

import static org.assertj.core.api.Assertions.assertThat;

import java.lang.reflect.Method;
import java.util.Arrays;
import org.apache.ibatis.annotations.Select;
import org.junit.jupiter.api.Test;

class AdminRoleMenuMapperSqlTest {

    @Test
    void grantValidationLocksActiveMenuRowsBeforeRoleMenuMutation() throws Exception {
        String sql = String.join("\n", AdminRoleMenuMapper.class
                .getMethod("selectActiveMenuIds", java.util.Collection.class)
                .getAnnotation(Select.class)
                .value());

        assertThat(sql)
                .contains("status = 1")
                .contains("is_deleted = 0")
                .contains("ORDER BY id")
                .contains("FOR UPDATE");
    }

    @Test
    void a7HasRelationshipProbeButNoRoleGrantCascadeDeletePath() {
        assertThat(Arrays.stream(AdminRoleMenuMapper.class.getDeclaredMethods())
                .map(Method::getName))
                .contains("countActiveByMenuId")
                .doesNotContain("softDeleteByMenuId");
    }
}
