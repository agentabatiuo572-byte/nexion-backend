package ffdd.opsconsole.user.domain;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import ffdd.opsconsole.shared.api.PageResult;
import ffdd.opsconsole.user.dto.UserAssetAdjustmentQueryRequest;
import ffdd.opsconsole.user.dto.UserQueryRequest;

public interface UserOpsRepository extends UserSeedRepository {
    Map<String, Object> overview();

    List<UserAccountView> search(String keyword, String status, String kycStatus, int limit);

    PageResult<UserAccountView> pageProfiles(UserQueryRequest request);

    long countByKycStatus(String kycStatus);

    Optional<UserAccountView> findById(Long userId);

    Optional<Long> findUserIdByLookupKey(String lookupKey);

    void upsertUser360Seed(User360Seed seed);

    void upsertAccountActionSeeds();

    void upsertKycLedgerSeeds();

    void upsertAssetAdjustmentSeeds();

    void upsertSecuritySessionSeeds();

    Optional<String> findWalletAddressByUserId(Long userId);

    Optional<UserSecurityStatusView> securityStatus(Long userId);

    List<UserSecurityUserRow> lockedSecurityUsers(
            int shortLockThreshold,
            int longLockThreshold,
            int shortLockMinutes,
            int longLockHours,
            int limit);

    List<UserSessionView> sessions(Long userId, int limit);

    PageResult<UserSessionView> pageSessions(Long userId, int pageNum, int pageSize);

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

    Optional<UserImpersonationSessionView> findImpersonation(String sessionNo);

    void terminateImpersonation(String sessionNo, String reason, String operator);

    void updateUserStatus(Long userId, String status, String reason);

    void updateKycStatus(Long userId, String kycStatus, String reason);

    Optional<UserSessionView> findSession(String refreshTokenId);

    void revokeSession(String refreshTokenId, String reason);

    void revokeUserSessions(Long userId, String reason);

    void disableTwoFactor(Long userId);

    void markPasswordResetRequired(Long userId, String resetMarker);

    void resetLoginFailures(Long userId);

    void recordImpersonationSession(String sessionNo, Long userId, int ttlMinutes, String operator, String reason, LocalDateTime expiresAt);

    void createAssetAdjustment(
            String adjustmentNo,
            Long userId,
            String asset,
            String direction,
            BigDecimal amount,
            String reason,
            String operator);

    PageResult<UserAssetAdjustmentView> pageAssetAdjustments(UserAssetAdjustmentQueryRequest request);

    Optional<UserAssetAdjustmentView> findAssetAdjustment(String adjustmentNo);

    Long approveAssetAdjustmentAndPostLedger(UserAssetAdjustmentView adjustment, String checker, String reason);

    void reviewAssetAdjustment(String adjustmentNo, String status, String checker, String reason);
}
