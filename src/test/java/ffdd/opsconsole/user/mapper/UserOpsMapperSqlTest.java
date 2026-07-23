package ffdd.opsconsole.user.mapper;

import static org.assertj.core.api.Assertions.assertThat;

import org.apache.ibatis.annotations.Select;
import org.junit.jupiter.api.Test;

class UserOpsMapperSqlTest {
    @Test
    void c1UserQueriesReadK4EffectiveRiskScore() throws Exception {
        String countSql = String.join("\n", UserOpsMapper.class
                .getMethod("countUsersByQuery", ffdd.opsconsole.user.dto.UserQueryRequest.class, java.util.List.class)
                .getAnnotation(Select.class)
                .value());
        String pageSql = String.join("\n", UserOpsMapper.class
                .getMethod("pageUsers", ffdd.opsconsole.user.dto.UserQueryRequest.class, java.util.List.class, int.class, int.class)
                .getAnnotation(Select.class)
                .value());
        String detailSql = String.join("\n", UserOpsMapper.class
                .getMethod("findById", Long.class)
                .getAnnotation(Select.class)
                .value());

        assertThat(countSql)
                .contains("LEFT JOIN (", "nx_admin_risk_score_model", "rsm.band_high_min", "rsm.band_low_max")
                .contains("WHERE state = 'active'", "rs.as_of >= DATE_SUB(NOW(), INTERVAL 1 DAY)")
                .contains("LEFT JOIN nx_admin_risk_score_override rso")
                .contains("COALESCE(rso.override_score, rs.model_score) &gt;= #{query.riskMin}")
                .contains("COALESCE(rso.override_score, rs.model_score) &gt;= rsm.band_high_min")
                .contains("COALESCE(rso.override_score, rs.model_score) &gt;= rsm.band_low_max")
                .contains("COALESCE(rso.override_score, rs.model_score) &lt; rsm.band_high_min")
                .contains("SHA2(REGEXP_REPLACE(u.phone, '[^0-9]', ''), 256) = LOWER(#{query.phoneHash})")
                .contains("u.user_level = #{query.tier}")
                .contains("u.v_rank = #{query.vRank}")
                .contains("nx_deposit_order")
                .contains("u.created_at &gt;= CONCAT(#{query.joinedFrom}, ' 00:00:00')")
                .doesNotContain("u.phone LIKE");
        assertThat(pageSql)
                .contains("LEFT JOIN nx_admin_risk_score_override rso")
                .contains("COALESCE(rso.override_score, rs.model_score) AS riskScore")
                .contains("WHEN COALESCE(rso.override_score, rs.model_score) >= rsm.band_high_min THEN '高风险'")
                .contains("WHEN COALESCE(rso.override_score, rs.model_score) >= rsm.band_low_max THEN '中风险'")
                .doesNotContain("BETWEEN 40 AND 69", ">= 70 THEN '高风险'", ">= 40 THEN '中风险'");
        assertThat(detailSql)
                .contains("LEFT JOIN nx_admin_risk_score_override rso")
                .contains("COALESCE(rso.override_score, rs.model_score) AS riskScore")
                .contains("WHEN COALESCE(rso.override_score, rs.model_score) >= rsm.band_high_min THEN '高风险'")
                .contains("WHEN COALESCE(rso.override_score, rs.model_score) >= rsm.band_low_max THEN '中风险'")
                .contains("rs.as_of >= DATE_SUB(NOW(), INTERVAL 1 DAY)")
                .doesNotContain(">= 70 THEN '高风险'", ">= 40 THEN '中风险'");
    }

    @Test
    void c5SessionQueriesApplyTheConfiguredIdleBoundaryToCountsAndRows() throws Exception {
        String countSql = String.join("\n", UserOpsMapper.class
                .getMethod("countActiveSessionsByUser", Long.class, int.class)
                .getAnnotation(Select.class).value());
        String listSql = String.join("\n", UserOpsMapper.class
                .getMethod("sessions", Long.class, int.class, int.class)
                .getAnnotation(Select.class).value());
        String pageSql = String.join("\n", UserOpsMapper.class
                .getMethod("pageSessions", Long.class, int.class, int.class, int.class)
                .getAnnotation(Select.class).value());

        assertThat(countSql)
                .contains("COALESCE(last_active_at,updated_at,created_at)")
                .contains("INTERVAL #{idleDays} DAY");
        assertThat(listSql)
                .contains("INTERVAL #{idleDays} DAY")
                .contains("AS lastActiveAt");
        assertThat(pageSql)
                .contains("INTERVAL #{idleDays} DAY")
                .contains("AS lastActiveAt");
    }

    @Test
    void c5SecurityQueriesUseTheCanonicalLoginGuardFailureCount() throws Exception {
        String statusSql = String.join("\n", UserOpsMapper.class
                .getMethod("securityStatus", Long.class)
                .getAnnotation(Select.class).value());
        String lockedUsersSql = String.join("\n", UserOpsMapper.class
                .getMethod("lockedSecurityUsers", int.class, int.class, int.class, int.class, int.class)
                .getAnnotation(Select.class).value());

        String normalizedStatusSql = statusSql.replaceAll("\\s+", " ");
        String normalizedLockedUsersSql = lockedUsersSql.replaceAll("\\s+", " ");

        assertThat(normalizedStatusSql)
                .contains("MAX(g.failed_count)")
                .contains("GREATEST( COALESCE(s.login_fail_count, 0)");
        assertThat(normalizedLockedUsersSql)
                .contains("MAX(failed_count) AS failed_count")
                .contains("GREATEST(COALESCE(s.login_fail_count, 0), COALESCE(g.failed_count, 0))");
    }

    @Test
    void c2ActiveImpersonationCheckUsesALockingCurrentRead() throws Exception {
        String sql = String.join("\n", UserOpsMapper.class
                .getMethod("countActiveImpersonationsByUser", Long.class)
                .getAnnotation(Select.class).value());

        assertThat(sql)
                .contains("status='ACTIVE'")
                .contains("FOR UPDATE");
    }

    @Test
    void c6LoginLockFactQueryToleratesMalformedHistoricalPayloads() throws Exception {
        String sql = String.join("\n", UserOpsMapper.class
                .getMethod("countRegistrationLoginLocksToday", String.class)
                .getAnnotation(Select.class).value());

        assertThat(sql)
                .contains("JSON_VALID(payload)")
                .contains("CASE WHEN JSON_VALID(payload) THEN payload ELSE '{}'");
    }
}
