package ffdd.opsconsole.user.domain;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Optional;

public interface UserOpsRepository {
    Map<String, Object> overview();

    List<UserAccountView> search(String keyword, String status, String kycStatus, int limit);

    Optional<UserAccountView> findById(Long userId);

    List<UserSessionView> sessions(Long userId, int limit);

    void updateUserStatus(Long userId, String status, String reason);

    Optional<UserSessionView> findSession(String refreshTokenId);

    void revokeSession(String refreshTokenId, String reason);

    void recordImpersonationSession(String sessionNo, Long userId, int ttlMinutes, String operator, String reason, LocalDateTime expiresAt);

    void createAssetAdjustment(
            String adjustmentNo,
            Long userId,
            String asset,
            String direction,
            BigDecimal amount,
            String reason,
            String operator);
}
