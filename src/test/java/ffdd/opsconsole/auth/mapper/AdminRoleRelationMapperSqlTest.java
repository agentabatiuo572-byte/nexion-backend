package ffdd.opsconsole.auth.mapper;

import static org.assertj.core.api.Assertions.assertThat;

import org.apache.ibatis.annotations.Select;
import org.junit.jupiter.api.Test;

class AdminRoleRelationMapperSqlTest {
    @Test
    void activeMenuCodesUseMysql8CompatibleDeduplicationAndOrdering() throws Exception {
        String sql = String.join("\n", AdminRoleRelationMapper.class
                .getMethod("selectActiveMenuCodes", Long.class)
                .getAnnotation(Select.class)
                .value());

        assertThat(sql)
                .contains("GROUP BY m.menu_code")
                .contains("ORDER BY MIN(m.sort_order), MIN(m.id)")
                .doesNotContain("SELECT DISTINCT m.menu_code");
    }
}
