package ffdd.opsconsole.user.infrastructure;


import ffdd.opsconsole.shared.api.PageResult;
import ffdd.opsconsole.user.domain.UserAccountListEntryView;
import ffdd.opsconsole.user.domain.UserAccountView;
import ffdd.opsconsole.user.domain.UserAssetAdjustmentView;
import ffdd.opsconsole.user.domain.UserImpersonationSessionView;
import ffdd.opsconsole.user.domain.UserNotificationView;
import ffdd.opsconsole.user.domain.UserOpsRepository;
import ffdd.opsconsole.user.domain.UserSecurityStatusView;
import ffdd.opsconsole.user.domain.UserSessionView;
import ffdd.opsconsole.user.domain.UserTeamMemberView;
import ffdd.opsconsole.user.dto.UserAssetAdjustmentQueryRequest;
import ffdd.opsconsole.user.dto.UserQueryRequest;
import ffdd.opsconsole.user.mapper.UserOpsMapper;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Repository;

@Repository
@RequiredArgsConstructor
public class MybatisUserOpsRepository implements UserOpsRepository {
    private final UserOpsMapper mapper;

    @Override
    public Map<String, Object> overview() {
        Map<String, Object> overview = new LinkedHashMap<>();
        overview.put("totalUsers", mapper.countUsers());
        overview.put("activeUsers", mapper.countActiveUsers());
        overview.put("kycPending", mapper.countKycPending());
        overview.put("frozenUsers", mapper.countFrozenUsers());
        overview.put("activeSessions", mapper.countActiveSessions());
        return overview;
    }

    @Override
    public List<UserAccountView> search(String keyword, String status, String kycStatus, int limit) {
        return mapper.pageUsers(trim(keyword), statusList(status), normalize(kycStatus), null, 0, cappedLimit(limit));
    }

    @Override
    public PageResult<UserAccountView> pageProfiles(UserQueryRequest request) {
        String keyword = trim(request == null ? null : request.keyword());
        List<String> statuses = statusList(request == null ? null : request.status());
        String kycStatus = normalize(request == null ? null : request.kycStatus());
        Integer riskMin = normalizeRiskMin(request == null ? null : request.riskMin());
        int pageNum = page(request == null ? null : request.pageNum());
        int pageSize = pageSize(request == null ? null : request.pageSize());
        int offset = (pageNum - 1) * pageSize;
        long total = mapper.countUsersByQuery(keyword, statuses, kycStatus, riskMin);
        List<UserAccountView> records = total == 0
                ? List.of()
                : mapper.pageUsers(keyword, statuses, kycStatus, riskMin, offset, pageSize);
        return new PageResult<>(total, pageNum, pageSize, records);
    }

    @Override
    public long countByKycStatus(String kycStatus) {
        return mapper.countByKycStatus(kycStatus);
    }

    @Override
    public Optional<UserAccountView> findById(Long userId) {
        return Optional.ofNullable(mapper.findById(userId));
    }

    @Override
    public Optional<UserSecurityStatusView> securityStatus(Long userId) {
        return Optional.ofNullable(mapper.securityStatus(userId));
    }

    @Override
    public List<UserSessionView> sessions(Long userId, int limit) {
        return mapper.sessions(userId, limit);
    }

    @Override
    public PageResult<UserSessionView> pageSessions(Long userId, int pageNum, int pageSize) {
        int normalizedPageNum = page(pageNum);
        int normalizedPageSize = pageSize(pageSize);
        long total = mapper.countSessions(userId);
        List<UserSessionView> records = total == 0
                ? List.of()
                : mapper.pageSessions(userId, (normalizedPageNum - 1) * normalizedPageSize, normalizedPageSize);
        return new PageResult<>(total, normalizedPageNum, normalizedPageSize, records);
    }

    @Override
    public List<UserTeamMemberView> teamMembers(Long userId, int limit) {
        return mapper.teamMembers(userId, cappedLimit(limit));
    }

    @Override
    public long countTeamMembers(Long userId) {
        return mapper.countTeamMembers(userId);
    }

    @Override
    public long countDirectTeamMembers(Long userId) {
        return mapper.countDirectTeamMembers(userId);
    }

    @Override
    public BigDecimal sumTeamVolume(Long userId) {
        BigDecimal volume = mapper.sumTeamVolume(userId);
        return volume == null ? BigDecimal.ZERO : volume;
    }

    @Override
    public List<UserNotificationView> notifications(Long userId, int limit) {
        return mapper.notifications(userId, cappedLimit(limit));
    }

    @Override
    public long countUnreadNotifications(Long userId) {
        return mapper.countUnreadNotifications(userId);
    }

    @Override
    public long countPendingNotifications(Long userId) {
        return mapper.countPendingNotifications(userId);
    }

    @Override
    public long countFailedNotifications(Long userId) {
        return mapper.countFailedNotifications(userId);
    }

    @Override
    public List<UserAccountListEntryView> accountLists(String status, int limit) {
        return mapper.accountLists(normalize(status), limit);
    }

    @Override
    public Optional<UserAccountListEntryView> findAccountList(Long userId) {
        return Optional.ofNullable(mapper.findAccountList(userId));
    }

    @Override
    public void upsertAccountList(Long userId, String kind, String reason, String operator, LocalDateTime expiresAt) {
        mapper.upsertAccountList(userId, kind, reason, operator, expiresAt);
    }

    @Override
    public void removeAccountList(Long userId, String reason, String operator) {
        mapper.removeAccountList(userId, reason, operator);
    }

    @Override
    public List<UserImpersonationSessionView> impersonations(int limit) {
        return mapper.impersonations(limit);
    }

    @Override
    public Optional<UserImpersonationSessionView> findImpersonation(String sessionNo) {
        return Optional.ofNullable(mapper.findImpersonation(trim(sessionNo)));
    }

    @Override
    public void terminateImpersonation(String sessionNo, String reason, String operator) {
        mapper.terminateImpersonation(trim(sessionNo), reason, operator);
    }

    @Override
    public void updateUserStatus(Long userId, String status, String reason) {
        mapper.updateUserStatus(userId, status);
    }

    @Override
    public void updateKycStatus(Long userId, String kycStatus, String reason) {
        mapper.updateKycStatus(userId, kycStatus);
    }

    @Override
    public Optional<UserSessionView> findSession(String refreshTokenId) {
        return Optional.ofNullable(mapper.findSession(refreshTokenId));
    }

    @Override
    public void revokeSession(String refreshTokenId, String reason) {
        mapper.revokeSession(refreshTokenId);
    }

    @Override
    public void revokeUserSessions(Long userId, String reason) {
        mapper.revokeUserSessions(userId);
    }

    @Override
    public void disableTwoFactor(Long userId) {
        mapper.disableTwoFactor(userId);
    }

    @Override
    public void markPasswordResetRequired(Long userId, String resetMarker) {
        mapper.markPasswordResetRequired(userId, resetMarker);
    }

    @Override
    public void resetLoginFailures(Long userId) {
        mapper.resetLoginFailures(userId);
    }

    @Override
    public void recordImpersonationSession(String sessionNo, Long userId, int ttlMinutes, String operator, String reason, LocalDateTime expiresAt) {
        mapper.insertImpersonationSession(sessionNo, userId, ttlMinutes, operator, reason, expiresAt);
    }

    @Override
    public void createAssetAdjustment(String adjustmentNo, Long userId, String asset, String direction, BigDecimal amount, String reason, String operator) {
        mapper.insertAssetAdjustment(adjustmentNo, userId, asset, direction, amount, reason, operator);
    }

    @Override
    public PageResult<UserAssetAdjustmentView> pageAssetAdjustments(UserAssetAdjustmentQueryRequest request) {
        String status = normalize(request == null ? null : request.status());
        String asset = normalize(request == null ? null : request.asset());
        Long userId = request == null ? null : request.userId();
        String keyword = trim(request == null ? null : request.keyword());
        Boolean historyOnly = request == null ? null : request.historyOnly();
        int pageNum = page(request == null ? null : request.pageNum());
        int pageSize = pageSize(request == null ? null : request.pageSize());
        int offset = (pageNum - 1) * pageSize;
        long total = mapper.countAssetAdjustments(status, asset, userId, keyword, historyOnly);
        List<UserAssetAdjustmentView> records = total == 0
                ? List.of()
                : mapper.pageAssetAdjustments(status, asset, userId, keyword, historyOnly, offset, pageSize);
        return new PageResult<>(total, pageNum, pageSize, records);
    }

    @Override
    public Optional<UserAssetAdjustmentView> findAssetAdjustment(String adjustmentNo) {
        return Optional.ofNullable(mapper.findAssetAdjustment(trim(adjustmentNo)));
    }

    @Override
    public void reviewAssetAdjustment(String adjustmentNo, String status, String checker, String reason) {
        mapper.reviewAssetAdjustment(adjustmentNo, status, checker, reason);
    }

    private String normalize(String value) {
        String trimmed = trim(value);
        return trimmed == null ? null : trimmed.toUpperCase(Locale.ROOT);
    }

    private List<String> statusList(String value) {
        String trimmed = trim(value);
        if (trimmed == null) {
            return List.of();
        }
        return Arrays.stream(trimmed.split(","))
                .map(this::normalize)
                .filter(item -> item != null && !item.isBlank())
                .distinct()
                .toList();
    }

    private Integer normalizeRiskMin(Integer value) {
        if (value == null) {
            return null;
        }
        return Math.max(0, Math.min(value, 100));
    }

    private String trim(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        return value.trim();
    }

    private int page(Integer value) {
        return value == null || value < 1 ? 1 : value;
    }

    private int pageSize(Integer value) {
        int normalized = value == null || value < 1 ? 20 : value;
        return Math.min(normalized, 100);
    }

    private int cappedLimit(int limit) {
        return Math.min(Math.max(limit, 1), 100);
    }
}
