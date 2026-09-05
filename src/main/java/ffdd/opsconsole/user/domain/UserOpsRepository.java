package ffdd.opsconsole.user.domain;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import ffdd.opsconsole.shared.api.PageResult;
import ffdd.opsconsole.user.dto.UserAssetAdjustmentQueryRequest;
import ffdd.opsconsole.user.dto.UserQueryRequest;

public interface UserOpsRepository {
    Map<String, Object> overview();

    List<UserAccountView> search(String keyword, String status, int limit);

    List<UserAccountControlFactView> accountControlFacts(int limit);

    Optional<UserAccountControlFactView> findAccountControlFact(Long userId);

    PageResult<UserAccountView> pageProfiles(UserQueryRequest request);

    PageResult<UserAccountView> pageSupportProfiles(UserQueryRequest request);

    Optional<UserAccountView> findById(Long userId);

    List<UserReadonlyDeviceView> readonlyDevices(Long userId, int limit);

    Optional<Long> findUserIdByLookupKey(String lookupKey);

    Optional<String> findWalletAddressByUserId(Long userId);

    Optional<UserSecurityStatusView> securityStatus(Long userId);

    List<UserSecurityUserRow> lockedSecurityUsers(
            int shortLockThreshold,
            int longLockThreshold,
            int shortLockMinutes,
            int longLockHours,
            int limit);

    List<UserSessionView> sessions(Long userId, int limit);

    default List<UserSessionView> sessions(Long userId, int limit, int idleDays) {
        return sessions(userId, limit);
    }

    long countActiveSessions(Long userId);

    default long countActiveSessions(Long userId, int idleDays) {
        return countActiveSessions(userId);
    }

    default long countActiveShortLocks(int longThreshold) { return 0L; }

    default long countActiveLongLocks(int longThreshold) { return 0L; }

    default long countRefreshTokenReuseToday() { return 0L; }

    default long countRegistrationOtpToday() { return 0L; }

    default long countRegistrationCaptchaTriggeredToday() { return 0L; }

    default long countRegistrationLoginLocksToday(String lockType) { return 0L; }

    default long countRegistrationStuffingClusters7d() { return 0L; }

    PageResult<UserSessionView> pageSessions(Long userId, int pageNum, int pageSize);

    default PageResult<UserSessionView> pageSessions(Long userId, int pageNum, int pageSize, int idleDays) {
        return pageSessions(userId, pageNum, pageSize);
    }

    List<UserTeamMemberView> teamMembers(Long userId, int limit);

    long countTeamMembers(Long userId);

    long countDirectTeamMembers(Long userId);

    BigDecimal sumTeamVolume(Long userId);

    List<UserNotificationView> notifications(Long userId, int limit);

    long countUnreadNotifications(Long userId);

    long countPendingNotifications(Long userId);

    long countFailedNotifications(Long userId);

    List<UserAccountListEntryView> accountLists(String status, int limit);

    Optional<UserAccountListEntryView> findAccountList(Long userId);

    void upsertAccountList(Long userId, String kind, String reason, String operator, LocalDateTime expiresAt);

    void removeAccountList(Long userId, String reason, String operator);

    List<UserImpersonationSessionView> impersonations(int limit);

    List<UserImpersonationSessionView> impersonations(Long userId, int limit);

    long countImpersonations(Long userId);

    Optional<UserImpersonationSessionView> findImpersonation(String sessionNo);

    void lockUser(Long userId);

    boolean hasActiveImpersonation(Long userId);

    List<UserImpersonationSessionView> expiredActiveImpersonations(int limit);

    boolean expireActiveImpersonation(String sessionNo, String reason, String operator);

    boolean terminateActiveImpersonation(String sessionNo, String reason, String operator);

    boolean transitionUserStatus(Long userId, String expectedStatus, String status, String reason);

    boolean freezeUserStatusWithSource(
            Long userId, String expectedStatus, String reason, String operator, String source, String sourceRef);

    boolean isFrozenBySource(Long userId, String source);

    boolean restoreUserStatusByFreezeSource(Long userId, String source, String sourceRef);

    Optional<UserSessionView> findSession(String refreshTokenId);

    boolean revokeSession(String refreshTokenId, String reason);

    void revokeUserSessions(Long userId, String reason);

    default void revokeActiveUserSessions(Long userId, String reason, int idleDays) {
        revokeUserSessions(userId, reason);
    }

    boolean disableTwoFactor(Long userId);

    boolean markPasswordResetRequired(Long userId, String resetMarker);

    boolean resetLoginFailures(Long userId);

    void recordImpersonationSession(String sessionNo, Long userId, int ttlMinutes, String operator, String reason, LocalDateTime expiresAt);

    void createAssetAdjustment(
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
            String operator);

    PageResult<UserAssetAdjustmentView> pageAssetAdjustments(UserAssetAdjustmentQueryRequest request);

    Optional<UserAssetAdjustmentView> findAssetAdjustment(String adjustmentNo);

    Long approveAssetAdjustmentAndPostLedger(UserAssetAdjustmentView adjustment, String checker, String reason);

    boolean reviewAssetAdjustment(String adjustmentNo, String status, String checker, String reason);

    boolean withdrawAssetAdjustment(String adjustmentNo, String maker, String reason);

    boolean assetAdjustmentHasReversal(String adjustmentNo);

    BigDecimal findWalletPendingWithdraw(Long userId);
}
