package ffdd.opsconsole.user.mapper;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.apache.ibatis.annotations.Select;
import org.junit.jupiter.api.Test;

class UserOpsMapperSqlTest {
    @Test
    void c1UserQueriesReadK4EffectiveRiskScore() throws Exception {
        String countSql = String.join("\n", UserOpsMapper.class
                .getMethod("countUsersByQuery", String.class, List.class, String.class, Integer.class)
                .getAnnotation(Select.class)
                .value());
        String pageSql = String.join("\n", UserOpsMapper.class
                .getMethod("pageUsers", String.class, List.class, String.class, Integer.class, int.class, int.class)
                .getAnnotation(Select.class)
                .value());
        String detailSql = String.join("\n", UserOpsMapper.class
                .getMethod("findById", Long.class)
                .getAnnotation(Select.class)
                .value());

        assertThat(countSql)
                .contains("LEFT JOIN nx_admin_risk_score_override rso")
                .contains("COALESCE(rso.override_score, rs.model_score, 0) &gt;= #{riskMin}");
        assertThat(pageSql)
                .contains("LEFT JOIN nx_admin_risk_score_override rso")
                .contains("COALESCE(rso.override_score, rs.model_score) AS riskScore")
                .contains("WHEN COALESCE(rso.override_score, rs.model_score) >= 70 THEN '高风险'");
        assertThat(detailSql)
                .contains("LEFT JOIN nx_admin_risk_score_override rso")
                .contains("COALESCE(rso.override_score, rs.model_score) AS riskScore")
                .contains("WHEN COALESCE(rso.override_score, rs.model_score) >= 70 THEN '高风险'");
    }
}
