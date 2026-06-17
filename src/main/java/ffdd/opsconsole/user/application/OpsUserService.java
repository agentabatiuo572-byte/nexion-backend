package ffdd.opsconsole.user.application;

import ffdd.opsconsole.shared.api.ApiResult;
import ffdd.opsconsole.shared.audit.AuditLogService;
import ffdd.opsconsole.shared.audit.AuditLogWriteRequest;
import ffdd.opsconsole.common.api.OpsErrorCode;
import ffdd.opsconsole.common.boundary.ApplicationService;
import ffdd.opsconsole.treasury.facade.TreasuryCoverageFacade;
import ffdd.opsconsole.treasury.facade.TreasuryCoverageSnapshot;
import ffdd.opsconsole.user.domain.UserAccountView;
import ffdd.opsconsole.user.domain.UserOpsRepository;
import ffdd.opsconsole.user.domain.UserSessionView;
import ffdd.opsconsole.user.dto.UserAssetAdjustmentRequest;
import ffdd.opsconsole.user.dto.UserImpersonationRequest;
import ffdd.opsconsole.user.dto.UserQueryRequest;
import ffdd.opsconsole.user.dto.UserSessionRevokeRequest;
import ffdd.opsconsole.user.dto.UserStatusUpdateRequest;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.springframework.util.StringUtils;

@ApplicationService
public class OpsUserService {
    private static final Set<String> USER_STATUSES = Set.of("ACTIVE", "FROZEN", "BANNED", "RESTRICTED");
    private static final Set<String> ASSETS = Set.of("USDT", "NEX");
    private static final Set<String> DIRECTIONS = Set.of("CREDIT", "DEBIT");

    private final UserOpsRepository userRepository;
    private final TreasuryCoverageFacade coverageFacade;
    private final AuditLogService auditLogService;

    public OpsUserService(
            UserOpsRepository userRepository,
            TreasuryCoverageFacade coverageFacade,
            AuditLogService auditLogService) {
        this.userRepository = userRepository;
        this.coverageFacade = coverageFacade;
        this.auditLogService = auditLogService;
    }

    public ApiResult<Map<String, Object>> overview() {
        Map<String, Object> response = new LinkedHashMap<>(userRepository.overview());
        response.put("domain", "C");
        response.put("capabilities", List.of("UserProfile", "KycReview", "AccountSecurity", "ManualAssetAdjustment"));
        response.put("sunsetCompatibility", List.of("Premium history is read-only", "NEX v2 maturity is historical", "Points adjustments are rejected"));
        response.put("sources", List.of("nx_user", "nx_user_session", "nx_wallet_asset_adjustment"));
        return ApiResult.ok(response);
    }

    public ApiResult<List<UserAccountView>> profiles(UserQueryRequest request) {
        int limit = normalizeLimit(request == null ? null : request.limit(), 50, 100);
        return ApiResult.ok(userRepository.search(
                request == null ? null : request.keyword(),
                request == null ? null : request.status(),
                request == null ? null : request.kycStatus(),
                limit));
    }

    public ApiResult<UserAccountView> profile(Long userId) {
        if (userId == null || userId <= 0) {
            return ApiResult.fail(OpsErrorCode.VALIDATION_FAILED.httpStatus(), "USER_ID_REQUIRED");
        }
        return userRepository.findById(userId)
                .map(ApiResult::ok)
                .orElseGet(() -> ApiResult.fail(404, "USER_NOT_FOUND"));
    }

    public ApiResult<List<UserSessionView>> sessions(Long userId, Integer limit) {
        return ApiResult.ok(userRepository.sessions(userId, normalizeLimit(limit, 100, 200)));
    }

    public ApiResult<UserAccountView> updateStatus(Long userId, String idempotencyKey, UserStatusUpdateRequest request) {
        ApiResult<UserAccountView> guard = requireUserCommand(userId, idempotencyKey, request == null ? null : request.reason());
        if (guard != null) {
            return guard;
        }
        UserAccountView before = userRepository.findById(userId).orElse(null);
        if (before == null) {
            return ApiResult.fail(404, "USER_NOT_FOUND");
        }
        String nextStatus = normalizeUserStatus(request.status());
        if (nextStatus.equalsIgnoreCase(before.status())) {
            return ApiResult.fail(OpsErrorCode.INVALID_STATE_TRANSITION.httpStatus(), OpsErrorCode.INVALID_STATE_TRANSITION.name());
        }
        userRepository.updateUserStatus(userId, nextStatus, request.reason().trim());
        UserAccountView updated = userRepository.findById(userId)
                .orElse(new UserAccountView(
                        before.id(), before.userNo(), before.nickname(), before.phoneMasked(), before.countryCode(), nextStatus,
                        before.kycStatus(), before.userLevel(), before.vRank(), before.twoFactorEnabled(), before.walletUsdt(),
                        before.walletNex(), before.registeredAt(), before.lastLoginAt()));
        audit("C1_USER_STATUS_CHANGED", "USER", String.valueOf(userId), userId, request.operator(), Map.of(
                "fromStatus", before.status(),
                "toStatus", nextStatus,
                "reason", request.reason().trim(),
                "idempotencyKey", idempotencyKey.trim()));
        return ApiResult.ok(updated);
    }

    public ApiResult<UserSessionView> revokeSession(String refreshTokenId, String idempotencyKey, UserSessionRevokeRequest request) {
        if (!StringUtils.hasText(refreshTokenId)) {
            return ApiResult.fail(OpsErrorCode.VALIDATION_FAILED.httpStatus(), "REFRESH_TOKEN_ID_REQUIRED");
        }
        ApiResult<UserSessionView> guard = requireCommand(idempotencyKey, request == null ? null : request.reason());
        if (guard != null) {
            return guard;
        }
        UserSessionView session = userRepository.findSession(refreshTokenId.trim()).orElse(null);
        if (session == null) {
            return ApiResult.fail(404, "SESSION_NOT_FOUND");
        }
        if ("REVOKED".equalsIgnoreCase(session.status())) {
            return ApiResult.fail(OpsErrorCode.INVALID_STATE_TRANSITION.httpStatus(), OpsErrorCode.INVALID_STATE_TRANSITION.name());
        }
        userRepository.revokeSession(refreshTokenId.trim(), request.reason().trim());
        UserSessionView updated = userRepository.findSession(refreshTokenId.trim())
                .orElse(new UserSessionView(
                        session.userId(), session.refreshTokenId(), session.deviceName(), session.clientIpMasked(), "REVOKED",
                        session.issuedAt(), session.expiresAt(), LocalDateTime.now()));
        audit("C3_USER_SESSION_REVOKED", "USER_SESSION", refreshTokenId.trim(), session.userId(), request.operator(), Map.of(
                "status", "REVOKED",
                "reason", request.reason().trim(),
                "idempotencyKey", idempotencyKey.trim()));
        return ApiResult.ok(updated);
    }

    public ApiResult<Map<String, Object>> startImpersonation(Long userId, String idempotencyKey, UserImpersonationRequest request) {
        ApiResult<Map<String, Object>> guard = requireUserCommand(userId, idempotencyKey, request == null ? null : request.reason());
        if (guard != null) {
            return guard;
        }
        if (userRepository.findById(userId).isEmpty()) {
            return ApiResult.fail(404, "USER_NOT_FOUND");
        }
        int ttlMinutes = request.ttlMinutes() == null ? 15 : request.ttlMinutes();
        if (ttlMinutes < 5 || ttlMinutes > 60) {
            return ApiResult.fail(OpsErrorCode.VALIDATION_FAILED.httpStatus(), "IMPERSONATION_TTL_OUT_OF_RANGE");
        }
        String sessionNo = "IMP-" + UUID.randomUUID().toString().replace("-", "").substring(0, 16).toUpperCase(Locale.ROOT);
        LocalDateTime expiresAt = LocalDateTime.now().plusMinutes(ttlMinutes);
        String operator = operator(request.operator());
        userRepository.recordImpersonationSession(sessionNo, userId, ttlMinutes, operator, request.reason().trim(), expiresAt);
        Map<String, Object> response = new LinkedHashMap<>();
        response.put("sessionNo", sessionNo);
        response.put("userId", userId);
        response.put("status", "ACTIVE");
        response.put("ttlMinutes", ttlMinutes);
        response.put("expiresAt", expiresAt);
        response.put("boundary", "admin impersonation is audited and does not expose credentials");
        audit("C3_USER_IMPERSONATION_STARTED", "USER_IMPERSONATION", sessionNo, userId, operator, Map.of(
                "ttlMinutes", ttlMinutes,
                "reason", request.reason().trim(),
                "idempotencyKey", idempotencyKey.trim()));
        return ApiResult.ok(response);
    }

    public ApiResult<Map<String, Object>> createAssetAdjustment(
            Long userId,
            String idempotencyKey,
            UserAssetAdjustmentRequest request) {
        ApiResult<Map<String, Object>> guard = requireUserCommand(userId, idempotencyKey, request == null ? null : request.reason());
        if (guard != null) {
            return guard;
        }
        if (userRepository.findById(userId).isEmpty()) {
            return ApiResult.fail(404, "USER_NOT_FOUND");
        }
        String asset = normalizeAsset(request.asset());
        String direction = normalizeDirection(request.direction());
        BigDecimal amount = positiveAmount(request.amount());
        if ("CREDIT".equals(direction) && coverageBelowRedline()) {
            return ApiResult.fail(OpsErrorCode.COVERAGE_BELOW_REDLINE.httpStatus(), OpsErrorCode.COVERAGE_BELOW_REDLINE.name());
        }
        String adjustmentNo = "ADJ-" + UUID.randomUUID().toString().replace("-", "").substring(0, 16).toUpperCase(Locale.ROOT);
        String operator = operator(request.operator());
        userRepository.createAssetAdjustment(adjustmentNo, userId, asset, direction, amount, request.reason().trim(), operator);
        Map<String, Object> response = new LinkedHashMap<>();
        response.put("adjustmentNo", adjustmentNo);
        response.put("userId", userId);
        response.put("asset", asset);
        response.put("direction", direction);
        response.put("amount", amount);
        response.put("status", "PENDING_REVIEW");
        response.put("ledgerPosting", "deferred-to-D-domain-review");
        audit("C4_MANUAL_ASSET_ADJUSTMENT_CREATED", "WALLET_ASSET_ADJUSTMENT", adjustmentNo, userId, operator, Map.of(
                "asset", asset,
                "direction", direction,
                "amount", amount,
                "reason", request.reason().trim(),
                "idempotencyKey", idempotencyKey.trim()));
        return ApiResult.ok(response);
    }

    private <T> ApiResult<T> requireUserCommand(Long userId, String idempotencyKey, String reason) {
        if (userId == null || userId <= 0) {
            return ApiResult.fail(OpsErrorCode.VALIDATION_FAILED.httpStatus(), "USER_ID_REQUIRED");
        }
        return requireCommand(idempotencyKey, reason);
    }

    private <T> ApiResult<T> requireCommand(String idempotencyKey, String reason) {
        if (!StringUtils.hasText(idempotencyKey)) {
            return ApiResult.fail(OpsErrorCode.IDEMPOTENCY_KEY_REQUIRED.httpStatus(), OpsErrorCode.IDEMPOTENCY_KEY_REQUIRED.name());
        }
        if (!StringUtils.hasText(reason) || reason.trim().length() < 6) {
            return ApiResult.fail(OpsErrorCode.REASON_REQUIRED.httpStatus(), OpsErrorCode.REASON_REQUIRED.name());
        }
        return null;
    }

    private String normalizeUserStatus(String status) {
        String normalized = requireText(status, "USER_STATUS_REQUIRED").toUpperCase(Locale.ROOT);
        if (!USER_STATUSES.contains(normalized)) {
            throw new IllegalArgumentException("Unsupported C user status");
        }
        return normalized;
    }

    private String normalizeAsset(String asset) {
        String normalized = requireText(asset, "ASSET_REQUIRED").toUpperCase(Locale.ROOT);
        if (normalized.contains("POINT")) {
            throw new IllegalArgumentException("Points system is sunset");
        }
        if (!ASSETS.contains(normalized)) {
            throw new IllegalArgumentException("Unsupported asset for C manual adjustment");
        }
        return normalized;
    }

    private String normalizeDirection(String direction) {
        String normalized = requireText(direction, "DIRECTION_REQUIRED").toUpperCase(Locale.ROOT);
        if (!DIRECTIONS.contains(normalized)) {
            throw new IllegalArgumentException("Unsupported adjustment direction");
        }
        return normalized;
    }

    private BigDecimal positiveAmount(String raw) {
        BigDecimal amount;
        try {
            amount = new BigDecimal(requireText(raw, "AMOUNT_REQUIRED").replace(",", ""));
        } catch (NumberFormatException ex) {
            throw new IllegalArgumentException("Invalid adjustment amount", ex);
        }
        if (amount.compareTo(BigDecimal.ZERO) <= 0) {
            throw new IllegalArgumentException("Adjustment amount must be positive");
        }
        return amount.stripTrailingZeros();
    }

    private int normalizeLimit(Integer limit, int fallback, int max) {
        int value = limit == null ? fallback : limit;
        if (value < 1) {
            return fallback;
        }
        return Math.min(value, max);
    }

    private boolean coverageBelowRedline() {
        TreasuryCoverageSnapshot coverage = coverageFacade.snapshot();
        return coverage.coverageRatio().compareTo(coverage.redlinePct()) < 0;
    }

    private String requireText(String value, String message) {
        if (!StringUtils.hasText(value)) {
            throw new IllegalArgumentException(message);
        }
        return value.trim();
    }

    private String operator(String operator) {
        return StringUtils.hasText(operator) ? operator.trim() : "system";
    }

    private void audit(String action, String resourceType, String resourceId, Long userId, String operator, Map<String, Object> detail) {
        auditLogService.record(AuditLogWriteRequest.builder()
                .action(action)
                .resourceType(resourceType)
                .resourceId(resourceId)
                .bizNo(resourceId)
                .userId(userId)
                .actorType("ADMIN")
                .actorUsername(operator(operator))
                .result("SUCCESS")
                .riskLevel("HIGH")
                .detail(detail)
                .build());
    }
}
