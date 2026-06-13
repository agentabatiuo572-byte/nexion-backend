package ffdd.auth.service.impl;

import ffdd.auth.dto.UserPreferenceResponse;
import ffdd.auth.dto.UserPreferenceUpdateRequest;
import ffdd.auth.dto.UserResponse;
import ffdd.auth.dto.UserUpdateRequest;
import ffdd.auth.service.UserOpsService;
import ffdd.auth.service.UserProfileService;
import ffdd.common.exception.BizException;
import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class UserProfileServiceImpl implements UserProfileService {
    private final UserOpsService userOpsService;
    private final JdbcTemplate jdbcTemplate;

    @Override
    public UserResponse current() {
        return userOpsService.detail(currentUserId());
    }

    @Override
    public UserResponse update(UserUpdateRequest request) {
        return userOpsService.update(currentUserId(), request);
    }

    @Override
    public UserPreferenceResponse currentPreferences() {
        return preferencesFor(currentUserId());
    }

    @Override
    public UserPreferenceResponse updatePreferences(UserPreferenceUpdateRequest request) {
        if (request == null) {
            request = new UserPreferenceUpdateRequest();
        }
        Long userId = currentUserId();
        UserPreferenceResponse existing = preferencesFor(userId);
        boolean soundEnabled = coalesce(request.getSoundEnabled(), existing.getSoundEnabled());
        boolean hapticsEnabled = coalesce(request.getHapticsEnabled(), existing.getHapticsEnabled());
        boolean notifyCommission = coalesce(request.getNotifyCommission(), existing.getNotifyCommission());
        boolean notifyTeam = coalesce(request.getNotifyTeam(), existing.getNotifyTeam());
        boolean notifyStaking = coalesce(request.getNotifyStaking(), existing.getNotifyStaking());
        boolean notifyMarket = coalesce(request.getNotifyMarket(), existing.getNotifyMarket());
        boolean notifyGenesis = coalesce(request.getNotifyGenesis(), existing.getNotifyGenesis());
        boolean notifySystem = coalesce(request.getNotifySystem(), existing.getNotifySystem());
        jdbcTemplate.update("""
                INSERT INTO nx_user_preference
                    (user_id, sound_enabled, haptics_enabled, notify_commission, notify_team,
                     notify_staking, notify_market, notify_genesis, notify_system, created_at, updated_at, is_deleted)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, NOW(), NOW(), 0)
                ON DUPLICATE KEY UPDATE
                    sound_enabled = VALUES(sound_enabled),
                    haptics_enabled = VALUES(haptics_enabled),
                    notify_commission = VALUES(notify_commission),
                    notify_team = VALUES(notify_team),
                    notify_staking = VALUES(notify_staking),
                    notify_market = VALUES(notify_market),
                    notify_genesis = VALUES(notify_genesis),
                    notify_system = VALUES(notify_system),
                    updated_at = NOW(),
                    is_deleted = 0
                """,
                userId,
                soundEnabled, hapticsEnabled, notifyCommission, notifyTeam,
                notifyStaking, notifyMarket, notifyGenesis, notifySystem);
        return preferencesFor(userId);
    }

    private UserPreferenceResponse preferencesFor(Long userId) {
        try {
            Map<String, Object> row = jdbcTemplate.queryForMap("""
                    SELECT user_id AS userId,
                           sound_enabled AS soundEnabled,
                           haptics_enabled AS hapticsEnabled,
                           notify_commission AS notifyCommission,
                           notify_team AS notifyTeam,
                           notify_staking AS notifyStaking,
                           notify_market AS notifyMarket,
                           notify_genesis AS notifyGenesis,
                           notify_system AS notifySystem,
                           created_at AS createdAt,
                           updated_at AS updatedAt
                      FROM nx_user_preference
                     WHERE user_id = ?
                       AND is_deleted = 0
                     LIMIT 1
                    """, userId);
            return toPreferenceResponse(row, userId);
        } catch (DataAccessException ex) {
            UserPreferenceResponse response = new UserPreferenceResponse();
            response.setUserId(userId);
            response.setSoundEnabled(true);
            response.setHapticsEnabled(true);
            response.setNotifyCommission(true);
            response.setNotifyTeam(true);
            response.setNotifyStaking(true);
            response.setNotifyMarket(true);
            response.setNotifyGenesis(true);
            response.setNotifySystem(true);
            return response;
        }
    }

    private UserPreferenceResponse toPreferenceResponse(Map<String, Object> row, Long fallbackUserId) {
        UserPreferenceResponse response = new UserPreferenceResponse();
        response.setUserId(longValue(row.get("userId"), fallbackUserId));
        response.setSoundEnabled(booleanValue(row.get("soundEnabled")));
        response.setHapticsEnabled(booleanValue(row.get("hapticsEnabled")));
        response.setNotifyCommission(booleanValue(row.get("notifyCommission")));
        response.setNotifyTeam(booleanValue(row.get("notifyTeam")));
        response.setNotifyStaking(booleanValue(row.get("notifyStaking")));
        response.setNotifyMarket(booleanValue(row.get("notifyMarket")));
        response.setNotifyGenesis(booleanValue(row.get("notifyGenesis")));
        response.setNotifySystem(booleanValue(row.get("notifySystem")));
        response.setCreatedAt(localDateTime(row.get("createdAt")));
        response.setUpdatedAt(localDateTime(row.get("updatedAt")));
        return response;
    }

    private boolean coalesce(Boolean value, Boolean fallback) {
        return value != null ? value : fallback == null || fallback;
    }

    private boolean booleanValue(Object value) {
        if (value instanceof Boolean bool) {
            return bool;
        }
        if (value instanceof Number number) {
            return number.intValue() != 0;
        }
        return value == null || Boolean.parseBoolean(String.valueOf(value));
    }

    private Long longValue(Object value, Long fallback) {
        if (value instanceof Number number) {
            return number.longValue();
        }
        if (value == null) {
            return fallback;
        }
        try {
            return Long.parseLong(String.valueOf(value));
        } catch (NumberFormatException ex) {
            return fallback;
        }
    }

    private LocalDateTime localDateTime(Object value) {
        if (value instanceof LocalDateTime dateTime) {
            return dateTime;
        }
        if (value instanceof Timestamp timestamp) {
            return timestamp.toLocalDateTime();
        }
        return null;
    }

    private Long currentUserId() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication == null || authentication.getPrincipal() == null) {
            throw new BizException(401, "User is not authenticated");
        }
        try {
            return Long.parseLong(String.valueOf(authentication.getPrincipal()));
        } catch (NumberFormatException ex) {
            throw new BizException(401, "User identity is invalid");
        }
    }
}
