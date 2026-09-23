package ffdd.opsconsole.user.mapper;

import static org.assertj.core.api.Assertions.assertThat;

import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;
import org.junit.jupiter.api.Test;

class UserOpsMapperSqlTest {
    @Test
    void c5SessionRevocationKeepsExpiryAndAuditTimeInUtcPlusEight() throws Exception {
        String activeCount = String.join(" ", UserOpsMapper.class.getMethod("countActiveSessions")
                .getAnnotation(Select.class).value());
        assertThat(activeCount).contains("expires_at > DATE_ADD(UTC_TIMESTAMP(), INTERVAL 8 HOUR)");
        String detail = String.join(" ", UserOpsMapper.class.getMethod("findSession", String.class)
                .getAnnotation(Select.class).value());
        assertThat(detail).contains("expires_at &lt;= DATE_ADD(UTC_TIMESTAMP(), INTERVAL 8 HOUR)");
        for (var method : new java.lang.reflect.Method[]{
                UserOpsMapper.class.getMethod("revokeSession", String.class),
                UserOpsMapper.class.getMethod("revokeUserSessions", Long.class),
                UserOpsMapper.class.getMethod("revokeActiveUserSessions", Long.class, int.class)}) {
            String sql = String.join(" ", method.getAnnotation(Update.class).value());
            assertThat(sql).contains("revoked_at = DATE_ADD(UTC_TIMESTAMP(), INTERVAL 8 HOUR)")
                    .doesNotContain("NOW()");
        }
        String activeSql = String.join(" ", UserOpsMapper.class
                .getMethod("revokeActiveUserSessions", Long.class, int.class)
                .getAnnotation(Update.class).value());
        assertThat(activeSql).contains("DATE_SUB(DATE_ADD(UTC_TIMESTAMP(), INTERVAL 8 HOUR), INTERVAL #{idleDays} DAY)");
    }

    @Test
    void c1LastLoginUsesTheNewestSessionIssuanceOrAuditedLogin() throws Exception {
        String pageSql = String.join("\n", UserOpsMapper.class
                .getMethod("pageUsers", ffdd.opsconsole.user.dto.UserQueryRequest.class, java.util.List.class, int.class, int.class, String.class)
                .getAnnotation(Select.class).value());
        String detailSql = String.join("\n", UserOpsMapper.class
                .getMethod("findById", Long.class)
                .getAnnotation(Select.class).value());

        for (String sql : new String[]{pageSql, detailSql}) {
            String normalized = sql.replaceAll("\\s+", " ");
            assertThat(normalized)
                    .contains("COALESCE( (SELECT MAX(sess.created_at)")
                    .contains("s.last_login_at IS NULL OR sess.created_at > s.last_login_at")
                    .contains("s.last_login_at ) AS lastLoginAt")
                    .doesNotContain("MAX(COALESCE(sess.last_active_at, sess.created_at))");
        }
    }

    @Test
    void c1UserQueriesReadK4EffectiveRiskScore() throws Exception {
        String countSql = String.join("\n", UserOpsMapper.class
                .getMethod("countUsersByQuery", ffdd.opsconsole.user.dto.UserQueryRequest.class, java.util.List.class, String.class)
                .getAnnotation(Select.class)
                .value());
        String pageSql = String.join("\n", UserOpsMapper.class
                .getMethod("pageUsers", ffdd.opsconsole.user.dto.UserQueryRequest.class, java.util.List.class, int.class, int.class, String.class)
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
        String totalSql = String.join("\n", UserOpsMapper.class
                .getMethod("countSessionsByUser", Long.class)
                .getAnnotation(Select.class).value());
        String countSql = String.join("\n", UserOpsMapper.class
                .getMethod("countActiveSessionsByUser", Long.class, int.class)
                .getAnnotation(Select.class).value());
        String listSql = String.join("\n", UserOpsMapper.class
                .getMethod("sessions", Long.class, int.class, int.class)
                .getAnnotation(Select.class).value());
        String pageSql = String.join("\n", UserOpsMapper.class
                .getMethod("pageSessions", Long.class, int.class, int.class, int.class)
                .getAnnotation(Select.class).value());

        assertThat(totalSql)
                .contains("AND user_id = #{userId}");
        assertThat(countSql)
                .contains("COALESCE(last_active_at,updated_at,created_at)")
                .contains("UTC_TIMESTAMP()")
                .contains("INTERVAL #{idleDays} DAY");
        assertThat(listSql)
                .contains("INTERVAL #{idleDays} DAY")
                .contains("UTC_TIMESTAMP()")
                .contains("AS lastActiveAt");
        assertThat(pageSql)
                .contains("INTERVAL #{idleDays} DAY")
                .contains("UTC_TIMESTAMP()")
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

    /**
     * zentao #230:用户改名后,C1 推荐团队表格仍显示旧昵称。
     *
     * `nx_team_member.nickname` 是入队时写下的**反规范化副本**,用户改名它不会跟着变;
     * 而同一页的账户摘要读的是 `nx_user.nickname`(实时)。结果是同一个人的名字在一屏里对不上。
     * 修法是把实时值作为首选来源,反规范化列只留作兜底。
     */
    @Test
    void c1TeamMembersReadTheLiveNicknameInsteadOfTheDenormalizedSnapshot() throws Exception {
        String sql = String.join("\n", UserOpsMapper.class
                .getMethod("teamMembers", Long.class, int.class)
                .getAnnotation(Select.class)
                .value());

        assertThat(sql)
                .as("必须按 C1 展示的 memberNo 找到同一个实时用户，不能信任历史投影的 member_user_id")
                .contains("WHEN tm.member_no REGEXP '^U[0-9]{8,}$'")
                .contains("THEN CAST(SUBSTRING(tm.member_no, 2) AS UNSIGNED)")
                .contains("ELSE tm.member_user_id")
                .contains("COALESCE(u.nickname, tm.nickname) AS nickname")
                .as("不能再把反规范化列当作唯一来源")
                .doesNotContain("                   nickname,");
    }
}
