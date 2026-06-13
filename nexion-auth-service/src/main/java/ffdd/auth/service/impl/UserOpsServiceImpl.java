package ffdd.auth.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import ffdd.auth.domain.User;
import ffdd.auth.dto.UserImpersonationEndRequest;
import ffdd.auth.dto.UserImpersonationSessionResponse;
import ffdd.auth.dto.UserImpersonationStartRequest;
import ffdd.auth.dto.UserPasswordResetLinkRequest;
import ffdd.auth.dto.UserPasswordResetLinkResponse;
import ffdd.auth.dto.UserQueryRequest;
import ffdd.auth.dto.UserResponse;
import ffdd.auth.dto.UserSearchResponse;
import ffdd.auth.dto.UserSessionResponse;
import ffdd.auth.dto.UserSessionRevokeRequest;
import ffdd.auth.dto.UserStatusUpdateRequest;
import ffdd.auth.dto.UserTwoFactorAdminRequest;
import ffdd.auth.dto.UserTwoFactorAdminResponse;
import ffdd.auth.dto.UserUpdateRequest;
import ffdd.auth.mapper.UserMapper;
import ffdd.auth.service.UserOpsService;
import ffdd.common.exception.BizException;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.UUID;
import java.util.regex.Pattern;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.BeanUtils;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

@Service
@RequiredArgsConstructor
public class UserOpsServiceImpl implements UserOpsService {
    private static final Set<String> ALLOWED_STATUSES = Set.of("ACTIVE", "DISABLED", "FROZEN");
    private static final Set<String> BUILT_IN_AVATARS = Set.of("mech:lime", "mech:cyan", "mech:violet", "mech:amber", "mech:rose");
    private static final Pattern SAFE_OBJECT_FILE = Pattern.compile("[A-Za-z0-9._-]+");

    private final UserMapper userMapper;
    private final JdbcTemplate jdbcTemplate;

    @Override
    public Page<UserResponse> page(long current, long size, UserQueryRequest query) {
        Page<User> users = userMapper.selectPage(Page.of(current, size), buildQuery(query));
        Page<UserResponse> result = Page.of(users.getCurrent(), users.getSize(), users.getTotal());
        result.setRecords(users.getRecords().stream().map(this::toResponse).toList());
        return result;
    }

    @Override
    public List<UserSearchResponse> search(String keyword, int limit) {
        String normalized = keyword == null ? "" : keyword.trim();
        if (normalized.length() < 2) {
            return List.of();
        }
        int safeLimit = Math.min(Math.max(limit, 1), 10);
        List<User> users = userMapper.selectList(new LambdaQueryWrapper<User>()
                .eq(User::getIsDeleted, 0)
                .and(wrapper -> wrapper
                        .like(User::getPhone, normalized)
                        .or()
                        .like(User::getNickname, normalized)
                        .or()
                        .like(User::getReferralCode, normalized))
                .orderByDesc(User::getId)
                .last("LIMIT " + safeLimit));
        return users.stream()
                .map(user -> new UserSearchResponse(
                        user.getId(),
                        user.getNickname(),
                        maskPhone(user.getCountryCode(), user.getPhone()),
                        user.getReferralCode(),
                        user.getUserLevel(),
                        user.getVRank(),
                        user.getStatus()))
                .toList();
    }

    @Override
    public UserResponse detail(Long id) {
        return toResponse(requireUser(id));
    }

    @Override
    public UserResponse update(Long id, UserUpdateRequest request) {
        User user = requireUser(id);
        if (request.getNickname() != null) {
            String nickname = request.getNickname().trim();
            if (!StringUtils.hasText(nickname)) {
                throw new BizException("Nickname cannot be blank");
            }
            user.setNickname(nickname);
        }
        if (request.getAvatarUrl() != null) {
            user.setAvatarUrl(normalizedAvatarUrl(request.getAvatarUrl()));
        }
        if (request.getLanguage() != null) {
            user.setLanguage(normalizedOptional(request.getLanguage()));
        }
        if (request.getRegion() != null) {
            user.setRegion(normalizedOptional(request.getRegion()));
        }
        if (request.getBio() != null) {
            user.setBio(normalizedOptional(request.getBio()));
        }
        if (request.getTimezone() != null) {
            user.setTimezone(normalizedOptional(request.getTimezone()));
        }
        userMapper.updateById(user);
        return detail(id);
    }

    @Override
    public UserResponse updateStatus(Long id, UserStatusUpdateRequest request) {
        User user = requireUser(id);
        String status = request.getStatus().trim().toUpperCase(Locale.ROOT);
        if (!ALLOWED_STATUSES.contains(status)) {
            throw new BizException("User status must be ACTIVE, DISABLED, or FROZEN");
        }
        user.setStatus(status);
        userMapper.updateById(user);
        return detail(id);
    }

    @Override
    public UserPasswordResetLinkResponse requestPasswordResetLink(Long id, UserPasswordResetLinkRequest request) {
        User user = requireUser(id);
        String resetRequestNo = "RESET-" + id + "-" + UUID.randomUUID().toString().replace("-", "").substring(0, 12).toUpperCase(Locale.ROOT);
        return new UserPasswordResetLinkResponse(
                id,
                resetRequestNo,
                "QUEUED",
                maskPhone(user.getCountryCode(), user.getPhone()),
                request.getOperator(),
                normalizedOptional(request.getReason()),
                LocalDateTime.now());
    }

    @Override
    public UserImpersonationSessionResponse startImpersonation(Long id, UserImpersonationStartRequest request) {
        requireUser(id);
        int ttlMinutes = Math.min(Math.max(request.getTtlMinutes() == null ? 30 : request.getTtlMinutes(), 1), 30);
        LocalDateTime startedAt = LocalDateTime.now();
        String sessionNo = "IMP-" + id + "-" + UUID.randomUUID().toString().replace("-", "").substring(0, 12).toUpperCase(Locale.ROOT);
        return new UserImpersonationSessionResponse(
                id,
                sessionNo,
                "ACTIVE",
                request.getOperator(),
                normalizedOptional(request.getReason()),
                ttlMinutes,
                startedAt,
                startedAt.plusMinutes(ttlMinutes),
                null);
    }

    @Override
    public UserImpersonationSessionResponse endImpersonation(String sessionNo, UserImpersonationEndRequest request) {
        String normalizedSessionNo = normalizedOptional(sessionNo);
        if (normalizedSessionNo == null || !normalizedSessionNo.startsWith("IMP-")) {
            throw new BizException("Invalid impersonation session");
        }
        return new UserImpersonationSessionResponse(
                parseImpersonationUserId(normalizedSessionNo),
                normalizedSessionNo,
                "ENDED",
                request.getOperator(),
                normalizedOptional(request.getReason()),
                null,
                null,
                null,
                LocalDateTime.now());
    }

    @Override
    public List<UserSessionResponse> listSessions(Long userId, int limit) {
        int safeLimit = Math.min(Math.max(limit, 1), 100);
        String sql = """
                        SELECT s.user_id AS userId,
                               s.refresh_token_id AS refreshTokenId,
                               s.device_name AS deviceName,
                               s.client_ip AS clientIp,
                               s.expires_at AS expiresAt,
                               s.revoked_at AS revokedAt,
                               s.created_at AS createdAt,
                               COALESCE(sec.two_factor_enabled, 0) AS twoFactorEnabled
                          FROM nx_user_session s
                          LEFT JOIN nx_user_security sec
                            ON sec.user_id = s.user_id
                           AND sec.is_deleted = 0
                         WHERE s.is_deleted = 0
                         %s
                         ORDER BY s.created_at DESC, s.id DESC
                         LIMIT ?
                        """.formatted(userId == null ? "" : "AND s.user_id = ?");
        List<Map<String, Object>> rows = userId == null
                ? jdbcTemplate.queryForList(sql, safeLimit)
                : jdbcTemplate.queryForList(sql, userId, safeLimit);
        return rows
                .stream()
                .map(this::toSessionResponse)
                .toList();
    }

    @Override
    public UserSessionResponse revokeSession(String refreshTokenId, UserSessionRevokeRequest request) {
        String normalized = normalizedOptional(refreshTokenId);
        if (normalized == null) {
            throw new BizException("Session token id is required");
        }
        int updated = jdbcTemplate.update("""
                UPDATE nx_user_session
                   SET revoked_at = COALESCE(revoked_at, NOW()),
                       updated_at = NOW()
                 WHERE refresh_token_id = ?
                   AND is_deleted = 0
                """, normalized);
        if (updated == 0) {
            throw new BizException("Session does not exist");
        }
        Map<String, Object> row = jdbcTemplate.queryForMap("""
                SELECT s.user_id AS userId,
                       s.refresh_token_id AS refreshTokenId,
                       s.device_name AS deviceName,
                       s.client_ip AS clientIp,
                       s.expires_at AS expiresAt,
                       s.revoked_at AS revokedAt,
                       s.created_at AS createdAt,
                       COALESCE(sec.two_factor_enabled, 0) AS twoFactorEnabled
                  FROM nx_user_session s
                  LEFT JOIN nx_user_security sec
                    ON sec.user_id = s.user_id
                   AND sec.is_deleted = 0
                 WHERE s.refresh_token_id = ?
                   AND s.is_deleted = 0
                 LIMIT 1
                """, normalized);
        return toSessionResponse(row);
    }

    @Override
    public UserTwoFactorAdminResponse disableTwoFactor(Long id, UserTwoFactorAdminRequest request) {
        requireUser(id);
        LocalDateTime updatedAt = LocalDateTime.now();
        jdbcTemplate.update("""
                INSERT INTO nx_user_security (user_id, two_factor_enabled, login_fail_count, created_at, updated_at, is_deleted)
                VALUES (?, 0, 0, NOW(), NOW(), 0)
                ON DUPLICATE KEY UPDATE
                    two_factor_enabled = 0,
                    login_fail_count = 0,
                    updated_at = NOW(),
                    is_deleted = 0
                """, id);
        return new UserTwoFactorAdminResponse(
                id,
                false,
                request.getOperator(),
                normalizedOptional(request.getReason()),
                updatedAt.format(DateTimeFormatter.ISO_LOCAL_DATE_TIME));
    }

    private User requireUser(Long id) {
        User user = userMapper.selectOne(new LambdaQueryWrapper<User>()
                .eq(User::getId, id)
                .eq(User::getIsDeleted, 0)
                .last("LIMIT 1"));
        if (user == null) {
            throw new BizException("User does not exist");
        }
        return user;
    }

    private LambdaQueryWrapper<User> buildQuery(UserQueryRequest query) {
        LambdaQueryWrapper<User> wrapper = new LambdaQueryWrapper<User>()
                .eq(User::getIsDeleted, 0);
        if (query == null) {
            return wrapper.orderByDesc(User::getId);
        }
        return wrapper
                .like(StringUtils.hasText(query.getPhone()), User::getPhone, query.getPhone())
                .like(StringUtils.hasText(query.getNickname()), User::getNickname, query.getNickname())
                .like(StringUtils.hasText(query.getReferralCode()), User::getReferralCode, query.getReferralCode())
                .eq(StringUtils.hasText(query.getStatus()), User::getStatus, normalizedUpper(query.getStatus()))
                .eq(StringUtils.hasText(query.getKycStatus()), User::getKycStatus, normalizedUpper(query.getKycStatus()))
                .eq(StringUtils.hasText(query.getUserLevel()), User::getUserLevel, normalizedUpper(query.getUserLevel()))
                .eq(StringUtils.hasText(query.getVRank()), User::getVRank, normalizedUpper(query.getVRank()))
                .orderByDesc(User::getId);
    }

    private UserResponse toResponse(User user) {
        UserResponse response = new UserResponse();
        BeanUtils.copyProperties(user, response);
        response.setPhoneMasked(maskPhone(user.getCountryCode(), user.getPhone()));
        applyUserLevelSummary(user, response);
        applyWalletSummary(user.getId(), response);
        applyPreferenceSummary(user.getId(), response);
        return response;
    }

    private void applyPreferenceSummary(Long userId, UserResponse response) {
        response.setSoundEnabled(true);
        response.setHapticsEnabled(true);
        response.setNotifyCommission(true);
        response.setNotifyTeam(true);
        response.setNotifyStaking(true);
        response.setNotifyMarket(true);
        response.setNotifyGenesis(true);
        response.setNotifySystem(true);
        try {
            Map<String, Object> prefs = jdbcTemplate.queryForMap("""
                    SELECT sound_enabled AS soundEnabled,
                           haptics_enabled AS hapticsEnabled,
                           notify_commission AS notifyCommission,
                           notify_team AS notifyTeam,
                           notify_staking AS notifyStaking,
                           notify_market AS notifyMarket,
                           notify_genesis AS notifyGenesis,
                           notify_system AS notifySystem
                      FROM nx_user_preference
                     WHERE user_id = ?
                       AND is_deleted = 0
                     LIMIT 1
                    """, userId);
            response.setSoundEnabled(booleanValue(prefs.get("soundEnabled")));
            response.setHapticsEnabled(booleanValue(prefs.get("hapticsEnabled")));
            response.setNotifyCommission(booleanValue(prefs.get("notifyCommission")));
            response.setNotifyTeam(booleanValue(prefs.get("notifyTeam")));
            response.setNotifyStaking(booleanValue(prefs.get("notifyStaking")));
            response.setNotifyMarket(booleanValue(prefs.get("notifyMarket")));
            response.setNotifyGenesis(booleanValue(prefs.get("notifyGenesis")));
            response.setNotifySystem(booleanValue(prefs.get("notifySystem")));
        } catch (DataAccessException ex) {
            // Missing preference rows inherit the all-enabled defaults above.
        }
    }

    private void applyWalletSummary(Long userId, UserResponse response) {
        response.setWalletPaired(false);
        try {
            Map<String, Object> profile = jdbcTemplate.queryForMap("""
                    SELECT wallet_address AS walletAddress
                      FROM nx_user_profile
                     WHERE user_id = ?
                       AND is_deleted = 0
                     LIMIT 1
                    """, userId);
            String walletAddress = stringValue(profile.get("walletAddress"), null);
            response.setWalletAddress(walletAddress);
            response.setWalletPaired(StringUtils.hasText(walletAddress));
        } catch (DataAccessException ex) {
            response.setWalletPaired(false);
        }
    }

    private void applyUserLevelSummary(User user, UserResponse response) {
        String currentLevel = StringUtils.hasText(user.getUserLevel()) ? user.getUserLevel() : "L1";
        response.setUserLevelName(currentLevel);
        response.setUserLevelProgressPercent(0);
        try {
            List<Map<String, Object>> levels = jdbcTemplate.queryForList("""
                    SELECT level_code AS levelCode,
                           level_name AS levelName,
                           entry_condition AS entryCondition,
                           sort_order AS sortOrder,
                           status
                      FROM nx_user_level_config
                     WHERE is_deleted = 0
                       AND status = 1
                     ORDER BY sort_order ASC, id ASC
                    """);
            if (levels.isEmpty()) {
                return;
            }
            int currentIndex = -1;
            for (int i = 0; i < levels.size(); i++) {
                if (currentLevel.equals(String.valueOf(levels.get(i).get("levelCode")))) {
                    currentIndex = i;
                    break;
                }
            }
            if (currentIndex < 0) {
                return;
            }
            Map<String, Object> current = levels.get(currentIndex);
            response.setUserLevelName(stringValue(current.get("levelName"), currentLevel));
            if (currentIndex >= levels.size() - 1) {
                response.setUserLevelProgressPercent(100);
                return;
            }
            Map<String, Object> next = levels.get(currentIndex + 1);
            response.setNextUserLevel(stringValue(next.get("levelCode"), null));
            response.setNextUserLevelName(stringValue(next.get("levelName"), response.getNextUserLevel()));
            response.setUserLevelProgressPercent(progressToNextLevel(user.getId(), next));
        } catch (DataAccessException ex) {
            response.setUserLevelName(currentLevel);
            response.setUserLevelProgressPercent(0);
        }
    }

    private int progressToNextLevel(Long userId, Map<String, Object> nextLevel) {
        String entryCondition = stringValue(nextLevel.get("entryCondition"), "").toLowerCase(Locale.ROOT);
        BigDecimal requiredUsdt = requiredEarnedUsdt(entryCondition);
        if (requiredUsdt.compareTo(BigDecimal.ZERO) > 0) {
            BigDecimal currentUsdt = userLifetimeEarnedUsdt(userId);
            if (currentUsdt.compareTo(BigDecimal.ZERO) <= 0) {
                return 0;
            }
            return clampPercent(currentUsdt
                    .multiply(BigDecimal.valueOf(100))
                    .divide(requiredUsdt, 0, RoundingMode.DOWN)
                    .intValue());
        }
        return 100;
    }

    private BigDecimal requiredEarnedUsdt(String entryCondition) {
        if (!StringUtils.hasText(entryCondition) || !entryCondition.contains("usdt")) {
            return BigDecimal.ZERO;
        }
        java.util.regex.Matcher matcher = java.util.regex.Pattern.compile("(\\d+(?:\\.\\d+)?)\\+?\\s*usdt").matcher(entryCondition);
        if (!matcher.find()) {
            return BigDecimal.ZERO;
        }
        return new BigDecimal(matcher.group(1));
    }

    private BigDecimal userLifetimeEarnedUsdt(Long userId) {
        try {
            BigDecimal value = jdbcTemplate.queryForObject("""
                    SELECT COALESCE(lifetime_earned, 0)
                      FROM nx_user_wallet
                     WHERE user_id = ?
                     LIMIT 1
                    """, BigDecimal.class, userId);
            return value == null ? BigDecimal.ZERO : value;
        } catch (DataAccessException ex) {
            return BigDecimal.ZERO;
        }
    }

    private int clampPercent(int value) {
        return Math.max(0, Math.min(100, value));
    }

    private String stringValue(Object value, String fallback) {
        if (value == null) {
            return fallback;
        }
        String normalized = String.valueOf(value).trim();
        return StringUtils.hasText(normalized) ? normalized : fallback;
    }

    private UserSessionResponse toSessionResponse(Map<String, Object> row) {
        String revokedAt = temporalString(row.get("revokedAt"));
        String expiresAt = temporalString(row.get("expiresAt"));
        return new UserSessionResponse(
                longValue(row.get("userId")),
                stringValue(row.get("refreshTokenId"), null),
                stringValue(row.get("deviceName"), "Unknown client"),
                stringValue(row.get("clientIp"), "-"),
                null,
                booleanValue(row.get("twoFactorEnabled")),
                sessionStatus(row.get("expiresAt"), row.get("revokedAt")),
                temporalString(row.get("createdAt")),
                expiresAt,
                revokedAt);
    }

    private String sessionStatus(Object expiresAt, Object revokedAt) {
        if (revokedAt != null) {
            return "REVOKED";
        }
        LocalDateTime expires = localDateTime(expiresAt);
        if (expires != null && expires.isBefore(LocalDateTime.now())) {
            return "EXPIRED";
        }
        return "ACTIVE";
    }

    private String temporalString(Object value) {
        LocalDateTime dateTime = localDateTime(value);
        return dateTime == null ? null : dateTime.format(DateTimeFormatter.ISO_LOCAL_DATE_TIME);
    }

    private LocalDateTime localDateTime(Object value) {
        if (value instanceof LocalDateTime dateTime) {
            return dateTime;
        }
        if (value instanceof java.sql.Timestamp timestamp) {
            return timestamp.toLocalDateTime();
        }
        if (value instanceof java.util.Date date) {
            return LocalDateTime.ofInstant(date.toInstant(), ZoneId.systemDefault());
        }
        return null;
    }

    private Long longValue(Object value) {
        if (value instanceof Number number) {
            return number.longValue();
        }
        if (value == null) {
            return null;
        }
        try {
            return Long.valueOf(String.valueOf(value));
        } catch (NumberFormatException ex) {
            return null;
        }
    }

    private boolean booleanValue(Object value) {
        if (value instanceof Boolean bool) {
            return bool;
        }
        if (value instanceof Number number) {
            return number.intValue() != 0;
        }
        return "true".equalsIgnoreCase(String.valueOf(value)) || "1".equals(String.valueOf(value));
    }

    private String maskPhone(String countryCode, String phone) {
        if (!StringUtils.hasText(phone)) {
            return "-";
        }
        String suffix = phone.length() <= 4 ? phone : phone.substring(phone.length() - 4);
        return (StringUtils.hasText(countryCode) ? countryCode : "") + "****" + suffix;
    }

    private String normalizedOptional(String value) {
        if (value == null) {
            return null;
        }
        String normalized = value.trim();
        return StringUtils.hasText(normalized) ? normalized : null;
    }

    private String normalizedAvatarUrl(String value) {
        String normalized = normalizedOptional(value);
        if (normalized == null || BUILT_IN_AVATARS.contains(normalized)) {
            return normalized;
        }
        String lower = normalized.toLowerCase(Locale.ROOT);
        if (lower.startsWith("http://") || lower.startsWith("https://") || lower.startsWith("data:") || lower.startsWith("blob:")) {
            throw new BizException("Avatar must be uploaded through media storage");
        }
        if (!normalized.startsWith("auth/users/avatar/")
                || normalized.length() > 255
                || normalized.startsWith("/")
                || normalized.endsWith("/")
                || normalized.contains("..")
                || normalized.indexOf('\\') >= 0
                || containsControlCharacters(normalized)
                || !SAFE_OBJECT_FILE.matcher(normalized.substring(normalized.lastIndexOf('/') + 1)).matches()) {
            throw new BizException("Avatar must be an uploaded user avatar object key");
        }
        return normalized;
    }

    private String normalizedUpper(String value) {
        return value == null ? null : value.trim().toUpperCase(Locale.ROOT);
    }

    private Long parseImpersonationUserId(String sessionNo) {
        String[] parts = sessionNo.split("-");
        if (parts.length < 3) {
            return null;
        }
        try {
            return Long.valueOf(parts[1]);
        } catch (NumberFormatException ex) {
            return null;
        }
    }

    private boolean containsControlCharacters(String value) {
        return value.indexOf('\n') >= 0 || value.indexOf('\r') >= 0 || value.indexOf('\t') >= 0;
    }
}
