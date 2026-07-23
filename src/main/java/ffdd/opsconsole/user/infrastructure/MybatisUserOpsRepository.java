package ffdd.opsconsole.user.infrastructure;


import ffdd.opsconsole.shared.api.PageResult;
import ffdd.opsconsole.user.domain.UserAccountListEntryView;
import ffdd.opsconsole.user.domain.UserAccountControlFactView;
import ffdd.opsconsole.user.domain.UserAccountView;
import ffdd.opsconsole.user.domain.UserAssetAdjustmentView;
import ffdd.opsconsole.user.domain.UserImpersonationSessionView;
import ffdd.opsconsole.user.domain.UserKycRecord;
import ffdd.opsconsole.user.domain.UserKycStatusHistoryView;
import ffdd.opsconsole.user.domain.UserKycReverificationView;
import ffdd.opsconsole.user.domain.UserNotificationView;
import ffdd.opsconsole.user.domain.UserOpsRepository;
import ffdd.opsconsole.user.domain.UserReadonlyDeviceView;
import ffdd.opsconsole.user.domain.UserSecurityStatusView;
import ffdd.opsconsole.user.domain.UserSecurityUserRow;
import ffdd.opsconsole.user.domain.UserSessionView;
import ffdd.opsconsole.user.domain.UserTeamMemberView;
import ffdd.opsconsole.user.dto.UserAssetAdjustmentQueryRequest;
import ffdd.opsconsole.user.dto.UserQueryRequest;
import ffdd.opsconsole.user.mapper.UserOpsMapper;
import java.math.BigDecimal;
import java.math.RoundingMode;
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
        overview.put("twoFactorEnabledUsers", mapper.countTwoFactorEnabledUsers());
        overview.put("lockedShort", mapper.countActiveShortLocks(10));
        overview.put("lockedLong", mapper.countActiveLongLocks(10));
        overview.put("tokenReuseToday", mapper.countEventToday("auth.refresh_token_reuse_detected"));
        overview.put("trustListCount", mapper.countActiveAccountListByKind("ALLOW"));
        overview.put("blockedListCount", mapper.countActiveAccountListByKind("BLOCK"));
        overview.put("activeImpersonations", mapper.countActiveImpersonations());
        overview.put("totalAccountLists", mapper.countAccountLists());
        overview.put("totalImpersonations", mapper.countImpersonations());
        overview.put("totalSessions", mapper.countSessions());
        return overview;
    }

    @Override
    public List<UserAccountView> search(String keyword, String status, String kycStatus, int limit) {
        UserQueryRequest query = UserQueryRequest.basic(
                trim(keyword), status, normalize(kycStatus), null, 1, cappedLimit(limit), null);
        return mapper.pageUsers(query, statusList(status), 0, cappedLimit(limit));
    }

    @Override
    public List<UserAccountControlFactView> accountControlFacts(int limit) {
        return mapper.accountControlFacts(Math.max(1, Math.min(limit, 200)));
    }

    @Override
    public Optional<UserAccountControlFactView> findAccountControlFact(Long userId) {
        return Optional.ofNullable(mapper.findAccountControlFact(userId));
    }

    @Override
    public PageResult<UserAccountView> pageProfiles(UserQueryRequest request) {
        List<String> statuses = statusList(request == null ? null : request.status());
        int pageNum = page(request == null ? null : request.pageNum());
        int pageSize = profilePageSize(request == null ? null : request.pageSize());
        UserQueryRequest query = normalizeProfileQuery(request, pageNum, pageSize);
        int offset = (pageNum - 1) * pageSize;
        long total = mapper.countUsersByQuery(query, statuses);
        List<UserAccountView> records = total == 0
                ? List.of()
                : mapper.pageUsers(query, statuses, offset, pageSize);
        return new PageResult<>(total, pageNum, pageSize, records);
    }

    @Override
    public long countByKycStatus(String kycStatus) {
        return mapper.countByKycStatus(kycStatus);
    }

    @Override
    public PageResult<UserKycRecord> pageKycRecords(String kycStatus, int pageNum, int pageSize) {
        int normalizedPage = page(pageNum);
        int normalizedSize = pageSize(pageSize);
        String normalizedStatus = normalize(kycStatus);
        long total = mapper.countKycRecords(normalizedStatus);
        List<UserKycRecord> records = total == 0
                ? List.of()
                : mapper.pageKycRecords(normalizedStatus, (normalizedPage - 1) * normalizedSize, normalizedSize);
        return new PageResult<>(total, normalizedPage, normalizedSize, records);
    }

    @Override
    public Optional<UserKycRecord> findKycRecord(Long userId) {
        return Optional.ofNullable(mapper.findKycRecord(userId));
    }

    @Override
    public List<UserKycStatusHistoryView> kycStatusHistory(Long userId, int limit) {
        return mapper.kycStatusHistory(userId, Math.max(1, Math.min(limit, 100)));
    }

    @Override
    public boolean transitionKycStatus(
            Long userId, String expectedStatus, long expectedVersion, String nextStatus,
            String reasonCode, String reason, String evidenceRef, String source,
            String operator, String idempotencyKey, String ticketId) {
        if (mapper.transitionKycProfile(userId, expectedStatus, expectedVersion, nextStatus, source, operator) == 0) {
            return false;
        }
        mapper.updateKycStatus(userId, nextStatus);
        mapper.insertKycStatusHistory(userId, expectedStatus, nextStatus, reasonCode, reason,
                evidenceRef, source, operator, idempotencyKey, ticketId);
        return true;
    }

    @Override
    public Optional<UserAccountView> findById(Long userId) {
        return Optional.ofNullable(mapper.findById(userId));
    }

    @Override
    public List<UserReadonlyDeviceView> readonlyDevices(Long userId, int limit) {
        return mapper.readonlyDevices(userId, Math.max(1, Math.min(limit, 50)));
    }

    @Override
    public Optional<String> findWalletAddressByUserId(Long userId) {
        return Optional.ofNullable(mapper.findWalletAddressByUserId(userId)).filter(value -> !value.isBlank());
    }

    @Override
    public Optional<Long> findUserIdByLookupKey(String lookupKey) {
        return Optional.ofNullable(mapper.findUserIdByLookupKey(trim(lookupKey)));
    }

    @Override
    public Optional<UserSecurityStatusView> securityStatus(Long userId) {
        return Optional.ofNullable(mapper.securityStatus(userId));
    }

    @Override
    public List<UserKycReverificationView> availableC5KycReverifications(Long userId, int rememberDays) {
        return mapper.availableC5KycReverifications(userId, Math.max(1, Math.min(rememberDays, 30)));
    }

    @Override
    public boolean canUseC5KycReverification(
            Long userId, String ticketId, String action, int rememberDays, String idempotencyKey) {
        return mapper.countUsableC5KycReverification(
                userId, ticketId, action, Math.max(1, Math.min(rememberDays, 30)), idempotencyKey) == 1;
    }

    @Override
    public boolean consumeC5KycReverification(
            Long userId, String ticketId, String action, String idempotencyKey, String operator) {
        try {
            return mapper.consumeC5KycReverification(userId, ticketId, action, idempotencyKey, operator) == 1;
        } catch (org.springframework.dao.DuplicateKeyException duplicate) {
            return canUseC5KycReverification(userId, ticketId, action, 30, idempotencyKey);
        }
    }

    @Override
    public List<UserSecurityUserRow> lockedSecurityUsers(
            int shortLockThreshold,
            int longLockThreshold,
            int shortLockMinutes,
            int longLockHours,
            int limit) {
        int normalizedShortThreshold = Math.max(shortLockThreshold, 1);
        int normalizedLongThreshold = Math.max(longLockThreshold, normalizedShortThreshold);
        return mapper.lockedSecurityUsers(
                normalizedShortThreshold,
                normalizedLongThreshold,
                Math.max(shortLockMinutes, 1),
                Math.max(longLockHours, 1),
                cappedLimit(limit));
    }

    @Override
    public List<UserSessionView> sessions(Long userId, int limit) {
        return sessions(userId, limit, 30);
    }

    @Override
    public List<UserSessionView> sessions(Long userId, int limit, int idleDays) {
        return mapper.sessions(userId, limit, Math.max(1, Math.min(idleDays, 90)));
    }

    @Override
    public long countActiveSessions(Long userId) {
        return countActiveSessions(userId, 30);
    }

    @Override
    public long countActiveSessions(Long userId, int idleDays) {
        return mapper.countActiveSessionsByUser(userId, Math.max(1, Math.min(idleDays, 90)));
    }

    @Override
    public long countActiveShortLocks(int longThreshold) {
        return mapper.countActiveShortLocks(Math.max(1, longThreshold));
    }

    @Override
    public long countActiveLongLocks(int longThreshold) {
        return mapper.countActiveLongLocks(Math.max(1, longThreshold));
    }

    @Override
    public long countRefreshTokenReuseToday() {
        return mapper.countEventToday("auth.refresh_token_reuse_detected");
    }

    @Override
    public long countRegistrationOtpToday() {
        return mapper.countRegistrationOtpToday();
    }

    @Override
    public long countRegistrationCaptchaTriggeredToday() {
        return mapper.countRegistrationCaptchaTriggeredToday();
    }

    @Override
    public long countRegistrationLoginLocksToday(String lockType) {
        return mapper.countRegistrationLoginLocksToday(lockType);
    }

    @Override
    public long countRegistrationStuffingClusters7d() {
        return mapper.countRegistrationStuffingClusters7d();
    }

    @Override
    public PageResult<UserSessionView> pageSessions(Long userId, int pageNum, int pageSize) {
        return pageSessions(userId, pageNum, pageSize, 30);
    }

    @Override
    public PageResult<UserSessionView> pageSessions(Long userId, int pageNum, int pageSize, int idleDays) {
        int normalizedPageNum = page(pageNum);
        int normalizedPageSize = pageSize(pageSize);
        long total = mapper.countSessions(userId);
        List<UserSessionView> records = total == 0
                ? List.of()
                : mapper.pageSessions(userId, (normalizedPageNum - 1) * normalizedPageSize, normalizedPageSize,
                        Math.max(1, Math.min(idleDays, 90)));
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
    public List<UserImpersonationSessionView> impersonations(Long userId, int limit) {
        return mapper.impersonationsByUser(userId, Math.max(1, Math.min(limit, 100)));
    }

    @Override
    public long countImpersonations(Long userId) {
        return mapper.countImpersonationsByUser(userId);
    }

    @Override
    public Optional<UserImpersonationSessionView> findImpersonation(String sessionNo) {
        return Optional.ofNullable(mapper.findImpersonation(trim(sessionNo)));
    }

    @Override
    public void lockUser(Long userId) {
        mapper.lockUser(userId);
    }

    @Override
    public boolean hasActiveImpersonation(Long userId) {
        return mapper.countActiveImpersonationsByUser(userId) > 0;
    }

    @Override
    public List<UserImpersonationSessionView> expiredActiveImpersonations(int limit) {
        return mapper.expiredActiveImpersonations(Math.max(1, Math.min(limit, 200)));
    }

    @Override
    public boolean expireActiveImpersonation(String sessionNo, String reason, String operator) {
        return mapper.expireActiveImpersonation(trim(sessionNo), reason, operator) == 1;
    }

    @Override
    public boolean terminateActiveImpersonation(String sessionNo, String reason, String operator) {
        return mapper.terminateActiveImpersonation(trim(sessionNo), reason, operator) == 1;
    }

    @Override
    public boolean transitionUserStatus(Long userId, String expectedStatus, String status, String reason) {
        return mapper.transitionUserStatus(userId, expectedStatus, status) == 1;
    }

    @Override
    public boolean freezeUserStatusWithSource(
            Long userId, String expectedStatus, String reason, String operator, String source, String sourceRef) {
        return mapper.freezeUserStatusWithSource(userId, expectedStatus, reason, operator, source, sourceRef) == 1;
    }

    @Override
    public boolean isFrozenBySource(Long userId, String source) {
        return mapper.countFrozenBySource(userId, source) > 0;
    }

    @Override
    public boolean restoreUserStatusByFreezeSource(Long userId, String source, String sourceRef) {
        return mapper.restoreUserStatusByFreezeSource(userId, source, sourceRef) == 1;
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
    public boolean revokeSession(String refreshTokenId, String reason) {
        return mapper.revokeSession(refreshTokenId) == 1;
    }

    @Override
    public void revokeUserSessions(Long userId, String reason) {
        mapper.revokeUserSessions(userId);
    }

    @Override
    public boolean disableTwoFactor(Long userId) {
        return mapper.disableTwoFactor(userId) == 1;
    }

    @Override
    public boolean markPasswordResetRequired(Long userId, String resetMarker) {
        // MySQL reports 2 affected rows when ON DUPLICATE KEY UPDATE changes an existing
        // security row; both the insert (1) and update (2) paths are successful writes.
        return mapper.markPasswordResetRequired(userId) > 0;
    }

    @Override
    public boolean resetLoginFailures(Long userId) {
        int securityRows = mapper.resetLoginFailures(userId);
        int guardRows = mapper.clearLoginGuardsByUserId(userId);
        return securityRows > 0 || guardRows > 0;
    }

    @Override
    public void recordImpersonationSession(String sessionNo, Long userId, int ttlMinutes, String operator, String reason, LocalDateTime expiresAt) {
        mapper.insertImpersonationSession(sessionNo, userId, ttlMinutes, operator, reason, expiresAt);
    }

    @Override
    public void createAssetAdjustment(
            String adjustmentNo,
            Long userId,
            String asset,
            String direction,
            BigDecimal amount,
            BigDecimal amountUsd,
            String reasonCode,
            String reason,
            String evidenceRef,
            String idempotencyKey,
            String reversalOf,
            String operator) {
        mapper.insertAssetAdjustment(
                adjustmentNo, userId, asset, direction, amount, amountUsd, reasonCode, reason,
                evidenceRef, idempotencyKey, reversalOf, operator);
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
    public Long approveAssetAdjustmentAndPostLedger(UserAssetAdjustmentView adjustment, String checker, String reason) {
        if (adjustment == null) {
            throw new IllegalArgumentException("ASSET_ADJUSTMENT_REQUIRED");
        }
        String lockedStatus = normalize(mapper.lockAssetAdjustmentStatus(adjustment.adjustmentNo()));
        if (!List.of("PENDING", "PENDING_REVIEW", "SUSPENDED").contains(lockedStatus)) {
            throw new IllegalStateException("ASSET_ADJUSTMENT_NOT_REVIEWABLE");
        }
        String asset = normalize(adjustment.asset());
        String direction = normalize(adjustment.direction());
        mapper.ensureUserWallet(adjustment.userId());
        int walletRows = mapper.applyAssetAdjustmentToWallet(
                adjustment.userId(),
                asset,
                direction,
                adjustment.amount());
        if (walletRows == 0) {
            throw new ffdd.opsconsole.shared.exception.BizException(422, "C3_INSUFFICIENT_BALANCE");
        }
        BigDecimal balanceAfter = mapper.findWalletAvailable(adjustment.userId(), asset);
        if (balanceAfter == null) {
            throw new IllegalStateException("ASSET_ADJUSTMENT_BALANCE_NOT_FOUND");
        }
        mapper.upsertApprovedAssetAdjustmentLedger(
                adjustment.adjustmentNo(),
                adjustment.userId(),
                asset,
                direction,
                adjustment.amount(),
                balanceAfter,
                ledgerRemark(reason));
        Long ledgerId = mapper.findAssetAdjustmentLedgerId(adjustment.adjustmentNo(), asset, direction);
        if (ledgerId == null) {
            throw new IllegalStateException("ASSET_ADJUSTMENT_LEDGER_NOT_FOUND");
        }
        int adjustmentRows = mapper.approveAssetAdjustmentWithLedger(adjustment.adjustmentNo(), checker, reason, ledgerId);
        if (adjustmentRows == 0) {
            throw new IllegalStateException("ASSET_ADJUSTMENT_REVIEW_UPDATE_FAILED");
        }
        return ledgerId;
    }

    @Override
    public boolean reviewAssetAdjustment(String adjustmentNo, String status, String checker, String reason) {
        return mapper.reviewAssetAdjustment(adjustmentNo, status, checker, reason) == 1;
    }

    @Override
    public boolean assetAdjustmentHasReversal(String adjustmentNo) {
        return mapper.countActiveAssetAdjustmentReversals(trim(adjustmentNo)) > 0;
    }

    @Override
    public BigDecimal findWalletPendingWithdraw(Long userId) {
        BigDecimal value = mapper.findWalletPendingWithdraw(userId);
        return value == null ? BigDecimal.ZERO : value;
    }

    private String normalize(String value) {
        String trimmed = trim(value);
        return trimmed == null ? null : trimmed.toUpperCase(Locale.ROOT);
    }

    private BigDecimal money(BigDecimal value) {
        return (value == null ? BigDecimal.ZERO : value).setScale(2, RoundingMode.HALF_UP);
    }

    private String ledgerRemark(String reason) {
        String remark = trim(reason);
        if (remark == null || remark.isBlank()) {
            return "C3 approved asset adjustment";
        }
        return remark.length() <= 255 ? remark : remark.substring(0, 255);
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

    private UserQueryRequest normalizeProfileQuery(UserQueryRequest request, int pageNum, int pageSize) {
        return new UserQueryRequest(
                trim(request == null ? null : request.keyword()),
                normalize(request == null ? null : request.status()),
                normalize(request == null ? null : request.kycStatus()),
                normalizeRiskMin(request == null ? null : request.riskMin()),
                pageNum,
                pageSize,
                null,
                request == null ? null : request.userId(),
                lower(request == null ? null : request.phoneHash()),
                trim(request == null ? null : request.phoneMasked()),
                normalize(request == null ? null : request.tier()),
                normalize(request == null ? null : request.vRank()),
                trim(request == null ? null : request.referralCode()),
                request == null ? null : request.depositMin(),
                request == null ? null : request.depositMax(),
                request == null ? null : request.walletUsdtMin(),
                request == null ? null : request.walletUsdtMax(),
                request == null ? null : request.walletNexMin(),
                request == null ? null : request.walletNexMax(),
                normalize(request == null ? null : request.riskBand()),
                trim(request == null ? null : request.joinedFrom()),
                trim(request == null ? null : request.joinedTo()));
    }

    private String lower(String value) {
        String trimmed = trim(value);
        return trimmed == null ? null : trimmed.toLowerCase(Locale.ROOT);
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

    private int profilePageSize(Integer value) {
        if (value == null) {
            return 50;
        }
        return Math.min(Math.max(value, 20), 200);
    }

    private int cappedLimit(int limit) {
        return Math.min(Math.max(limit, 1), 100);
    }
}
