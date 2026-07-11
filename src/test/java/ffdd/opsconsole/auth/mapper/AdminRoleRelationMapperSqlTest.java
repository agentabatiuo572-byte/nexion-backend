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

    @Test
    void effectiveMenuNodesExposeOnlyActiveGrantedA7Metadata() throws Exception {
        String sql = String.join("\n", AdminRoleRelationMapper.class
                .getMethod("selectActiveMenuNodes", Long.class)
                .getAnnotation(Select.class)
                .value());

        assertThat(sql)
                .contains("rr.admin_id = #{adminId}")
                .contains("rr.is_deleted = 0")
                .contains("r.status = 1")
                .contains("rm.is_deleted = 0")
                .contains("m.status = 1")
                .contains("m.is_deleted = 0")
                .contains("p.menu_code AS parentCode")
                .contains("COALESCE(NULLIF(m.menu_name_zh, '')")
                .contains("GROUP BY m.menu_code")
                .doesNotContain("m.parent_id AS parentCode");
    }

    @Test
    void superAdminMenuNodesStillExcludeDisabledAndDeletedA7Nodes() throws Exception {
        String sql = String.join("\n", AdminRoleRelationMapper.class
                .getMethod("selectAllActiveMenuNodes")
                .getAnnotation(Select.class)
                .value());

        assertThat(sql)
                .contains("m.status = 1")
                .contains("m.is_deleted = 0")
                .doesNotContain("nx_admin_role_menu");
    }
}
