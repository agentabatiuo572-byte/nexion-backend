package ffdd.opsconsole.user.infrastructure;

import ffdd.opsconsole.user.domain.UserAccountView;
import ffdd.opsconsole.user.domain.UserOpsRepository;
import ffdd.opsconsole.user.domain.UserSessionView;
import java.math.BigDecimal;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.util.StringUtils;

@Repository
public class JdbcUserOpsRepository implements UserOpsRepository {
    private final JdbcTemplate jdbcTemplate;

    public JdbcUserOpsRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    @Override
    public Map<String, Object> overview() {
        Map<String, Object> overview = new LinkedHashMap<>();
        overview.put("totalUsers", count("SELECT COUNT(*) FROM nx_user WHERE is_deleted = 0"));
        overview.put("activeUsers", count("SELECT COUNT(*) FROM nx_user WHERE is_deleted = 0 AND COALESCE(status, 'ACTIVE') = 'ACTIVE'"));
        overview.put("kycPending", count("SELECT COUNT(*) FROM nx_user WHERE is_deleted = 0 AND COALESCE(kyc_status, 'PENDING') = 'PENDING'"));
        overview.put("frozenUsers", count("SELECT COUNT(*) FROM nx_user WHERE is_deleted = 0 AND COALESCE(status, 'ACTIVE') IN ('FROZEN', 'BANNED', 'RESTRICTED')"));
        overview.put("activeSessions", count("""
                SELECT COUNT(*) FROM nx_user_session
                 WHERE is_deleted = 0
                   AND revoked_at IS NULL
                   AND expires_at > NOW()
                """));
        return overview;
    }

    @Override
    public List<UserAccountView> search(String keyword, String status, String kycStatus, int limit) {
        List<Object> args = new ArrayList<>();
        StringBuilder sql = new StringBuilder("""
                SELECT u.id,
                       CONCAT('U', LPAD(u.id, 8, '0')) AS user_no,
                       u.nickname,
                       u.phone,
                       u.country_code,
                       COALESCE(u.status, 'ACTIVE') AS status,
                       COALESCE(u.kyc_status, 'PENDING') AS kyc_status,
                       u.user_level,
                       u.v_rank,
                       COALESCE(s.two_factor_enabled, 0) AS two_factor_enabled,
                       COALESCE(w.usdt_available, 0) AS wallet_usdt,
                       COALESCE(w.nex_available, 0) AS wallet_nex,
                       u.created_at,
                       s.last_login_at
                  FROM nx_user u
                  LEFT JOIN nx_user_security s ON s.user_id = u.id AND s.is_deleted = 0
                  LEFT JOIN nx_user_wallet w ON w.user_id = u.id AND w.is_deleted = 0
                 WHERE u.is_deleted = 0
                """);
        if (StringUtils.hasText(keyword)) {
            sql.append(" AND (u.nickname LIKE ? OR u.phone LIKE ? OR u.referral_code LIKE ? OR CAST(u.id AS CHAR) = ?) ");
            String like = "%" + keyword.trim() + "%";
            args.add(like);
            args.add(like);
            args.add(like);
            args.add(keyword.trim());
        }
        if (StringUtils.hasText(status)) {
            sql.append(" AND COALESCE(u.status, 'ACTIVE') = ? ");
            args.add(status.trim().toUpperCase());
        }
        if (StringUtils.hasText(kycStatus)) {
            sql.append(" AND COALESCE(u.kyc_status, 'PENDING') = ? ");
            args.add(kycStatus.trim().toUpperCase());
        }
        sql.append(" ORDER BY u.id DESC LIMIT ? ");
        args.add(limit);
        return jdbcTemplate.query(sql.toString(), this::mapUser, args.toArray());
    }

    @Override
    public Optional<UserAccountView> findById(Long userId) {
        List<UserAccountView> rows = jdbcTemplate.query("""
                SELECT u.id,
                       CONCAT('U', LPAD(u.id, 8, '0')) AS user_no,
                       u.nickname,
                       u.phone,
                       u.country_code,
                       COALESCE(u.status, 'ACTIVE') AS status,
                       COALESCE(u.kyc_status, 'PENDING') AS kyc_status,
                       u.user_level,
                       u.v_rank,
                       COALESCE(s.two_factor_enabled, 0) AS two_factor_enabled,
                       COALESCE(w.usdt_available, 0) AS wallet_usdt,
                       COALESCE(w.nex_available, 0) AS wallet_nex,
                       u.created_at,
                       s.last_login_at
                  FROM nx_user u
                  LEFT JOIN nx_user_security s ON s.user_id = u.id AND s.is_deleted = 0
                  LEFT JOIN nx_user_wallet w ON w.user_id = u.id AND w.is_deleted = 0
                 WHERE u.id = ? AND u.is_deleted = 0
                 LIMIT 1
                """, this::mapUser, userId);
        return rows.stream().findFirst();
    }

    @Override
    public List<UserSessionView> sessions(Long userId, int limit) {
        List<Object> args = new ArrayList<>();
        StringBuilder sql = new StringBuilder("""
                SELECT user_id,
                       refresh_token_id,
                       device_name,
                       client_ip,
                       CASE
                         WHEN revoked_at IS NOT NULL THEN 'REVOKED'
                         WHEN expires_at <= NOW() THEN 'EXPIRED'
                         ELSE 'ACTIVE'
                       END AS status,
                       created_at,
                       expires_at,
                       revoked_at
                  FROM nx_user_session
                 WHERE is_deleted = 0
                """);
        if (userId != null) {
            sql.append(" AND user_id = ? ");
            args.add(userId);
        }
        sql.append(" ORDER BY created_at DESC, id DESC LIMIT ? ");
        args.add(limit);
        return jdbcTemplate.query(sql.toString(), this::mapSession, args.toArray());
    }

    @Override
    public void updateUserStatus(Long userId, String status, String reason) {
        jdbcTemplate.update("""
                UPDATE nx_user
                   SET status = ?, updated_at = NOW()
                 WHERE id = ? AND is_deleted = 0
                """, status, userId);
    }

    @Override
    public Optional<UserSessionView> findSession(String refreshTokenId) {
        List<UserSessionView> rows = jdbcTemplate.query("""
                SELECT user_id,
                       refresh_token_id,
                       device_name,
                       client_ip,
                       CASE
                         WHEN revoked_at IS NOT NULL THEN 'REVOKED'
                         WHEN expires_at <= NOW() THEN 'EXPIRED'
                         ELSE 'ACTIVE'
                       END AS status,
                       created_at,
                       expires_at,
                       revoked_at
                  FROM nx_user_session
                 WHERE refresh_token_id = ? AND is_deleted = 0
                 LIMIT 1
                """, this::mapSession, refreshTokenId);
        return rows.stream().findFirst();
    }

    @Override
    public void revokeSession(String refreshTokenId, String reason) {
        jdbcTemplate.update("""
                UPDATE nx_user_session
                   SET revoked_at = NOW(), updated_at = NOW()
                 WHERE refresh_token_id = ? AND is_deleted = 0
                """, refreshTokenId);
    }

    @Override
    public void recordImpersonationSession(
            String sessionNo,
            Long userId,
            int ttlMinutes,
            String operator,
            String reason,
            LocalDateTime expiresAt) {
        jdbcTemplate.update("""
                INSERT INTO nx_user_impersonation_session (
                    session_no, user_id, status, ttl_minutes, operator, reason, expires_at, created_at, updated_at, is_deleted
                ) VALUES (?, ?, 'ACTIVE', ?, ?, ?, ?, NOW(), NOW(), 0)
                """, sessionNo, userId, ttlMinutes, operator, reason, expiresAt);
    }

    @Override
    public void createAssetAdjustment(
            String adjustmentNo,
            Long userId,
            String asset,
            String direction,
            BigDecimal amount,
            String reason,
            String operator) {
        jdbcTemplate.update("""
                INSERT INTO nx_wallet_asset_adjustment (
                    adjustment_no, user_id, asset, direction, amount, reason_code, reason, maker, status, created_at, updated_at, is_deleted
                ) VALUES (?, ?, ?, ?, ?, 'OPS_USER_ADJUSTMENT', ?, ?, 'PENDING_REVIEW', NOW(), NOW(), 0)
                """, adjustmentNo, userId, asset, direction, amount, reason, operator);
    }

    private UserAccountView mapUser(ResultSet rs, int rowNum) throws SQLException {
        return new UserAccountView(
                rs.getLong("id"),
                rs.getString("user_no"),
                rs.getString("nickname"),
                maskPhone(rs.getString("phone")),
                rs.getString("country_code"),
                rs.getString("status"),
                rs.getString("kyc_status"),
                rs.getString("user_level"),
                rs.getString("v_rank"),
                rs.getInt("two_factor_enabled") == 1,
                rs.getBigDecimal("wallet_usdt"),
                rs.getBigDecimal("wallet_nex"),
                time(rs.getTimestamp("created_at")),
                time(rs.getTimestamp("last_login_at")));
    }

    private UserSessionView mapSession(ResultSet rs, int rowNum) throws SQLException {
        return new UserSessionView(
                rs.getLong("user_id"),
                rs.getString("refresh_token_id"),
                rs.getString("device_name"),
                maskIp(rs.getString("client_ip")),
                rs.getString("status"),
                time(rs.getTimestamp("created_at")),
                time(rs.getTimestamp("expires_at")),
                time(rs.getTimestamp("revoked_at")));
    }

    private long count(String sql) {
        Long value = jdbcTemplate.queryForObject(sql, Long.class);
        return value == null ? 0L : value;
    }

    private LocalDateTime time(Timestamp timestamp) {
        return timestamp == null ? null : timestamp.toLocalDateTime();
    }

    private String maskPhone(String phone) {
        if (!StringUtils.hasText(phone) || phone.length() < 7) {
            return phone;
        }
        return phone.substring(0, 3) + "****" + phone.substring(phone.length() - 4);
    }

    private String maskIp(String ip) {
        if (!StringUtils.hasText(ip)) {
            return ip;
        }
        int lastDot = ip.lastIndexOf('.');
        return lastDot > 0 ? ip.substring(0, lastDot) + ".*" : ip;
    }
}
