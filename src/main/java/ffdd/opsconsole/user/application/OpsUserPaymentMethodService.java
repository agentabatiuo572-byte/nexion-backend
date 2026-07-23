package ffdd.opsconsole.user.application;

import ffdd.opsconsole.common.api.OpsErrorCode;
import ffdd.opsconsole.shared.audit.AuditLogService;
import ffdd.opsconsole.shared.audit.AuditLogWriteRequest;
import ffdd.opsconsole.shared.exception.BizException;
import ffdd.opsconsole.shared.idempotency.AdminIdempotencyService;
import ffdd.opsconsole.shared.security.AdminActorResolver;
import ffdd.opsconsole.user.dto.UserPaymentMethodCommandRequest;
import ffdd.opsconsole.user.mapper.UserPaymentMethodMapper;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

@Service
@RequiredArgsConstructor
public class OpsUserPaymentMethodService {
    private final UserPaymentMethodMapper mapper;
    private final AuditLogService audit;
    private final AdminIdempotencyService idempotency;

    public Map<String, Object> list(Long userId, boolean includeUnbound, int page, int pageSize) {
        requireUser(userId);
        int safePage = Math.max(1, page);
        int safeSize = Math.min(50, Math.max(1, pageSize));
        long total = mapper.countMethods(userId, includeUnbound);
        var items = mapper.listMethods(userId, includeUnbound, (safePage - 1) * safeSize, safeSize);
        audit.recordRequired(AuditLogWriteRequest.builder()
                .action("USER_PAYMENT_METHOD_LIST")
                .resourceType("USER")
                .resourceId(String.valueOf(userId))
                .userId(userId)
                .actorUsername(AdminActorResolver.resolve("SYSTEM"))
                .riskLevel("MEDIUM")
                .detail(Map.of("includeUnbound", includeUnbound, "page", safePage,
                        "pageSize", safeSize, "rowCount", items.size()))
                .build());
        return Map.of("items", items,
                "page", safePage, "pageSize", safeSize, "total", total);
    }

    @Transactional
    public Map<String, Object> unbind(Long userId, Long methodId, String key, UserPaymentMethodCommandRequest request) {
        validateCommand(key, request, true);
        return idempotency.execute("USER_PAYMENT_METHOD_UNBIND", key,
                hash(userId + ":" + methodId + ":" + request.expectedVersion() + ":" + request.reason()),
                Map.class, () -> doUnbind(userId, methodId, key, request));
    }

    @Transactional
    public Map<String, Object> notifyRebind(Long userId, Long methodId, String key, UserPaymentMethodCommandRequest request) {
        validateCommand(key, request, true);
        return idempotency.execute("USER_PAYMENT_METHOD_REBIND_NOTICE", key,
                hash(userId + ":" + methodId + ":" + request.expectedVersion() + ":" + request.reason()),
                Map.class, () -> doNotifyRebind(userId, methodId, key, request));
    }

    @Transactional
    public Map<String, Object> resetNickname(Long userId, String key, UserPaymentMethodCommandRequest request) {
        validateCommand(key, request, false);
        return idempotency.execute("USER_NICKNAME_RESET", key, hash(userId + ":" + request.reason()), Map.class, () -> {
            requireUser(userId);
            String before = mapper.currentNickname(userId);
            String nickname = "Nexion-" + hash(userId + ":" + key).substring(0, 8).toUpperCase();
            if (mapper.resetNickname(userId, nickname) != 1) {
                throw new BizException(OpsErrorCode.INVALID_STATE_TRANSITION.httpStatus(), "NICKNAME_RESET_CONFLICT");
            }
            audit("USER_NICKNAME_RESET", "USER", String.valueOf(userId), userId, request, key,
                    Map.of("before", before == null ? "" : before, "after", nickname));
            return Map.of("userId", userId, "nickname", nickname, "status", "RESET");
        });
    }

    private Map<String, Object> doUnbind(Long userId, Long methodId, String key, UserPaymentMethodCommandRequest request) {
        UserPaymentMethodMapper.PaymentMethodRow row = requireMethod(userId, methodId);
        if (row.trialGuard()) {
            throw new BizException(OpsErrorCode.INVALID_STATE_TRANSITION.httpStatus(), "PAYMENT_METHOD_TRIAL_GUARDED");
        }
        if (!"BOUND".equals(row.status())) {
            throw new BizException(OpsErrorCode.INVALID_STATE_TRANSITION.httpStatus(), "PAYMENT_METHOD_NOT_BOUND");
        }
        if (mapper.unbind(userId, methodId, request.expectedVersion(), request.reason().trim(), operator(request)) != 1) {
            throw new BizException(OpsErrorCode.INVALID_STATE_TRANSITION.httpStatus(), "PAYMENT_METHOD_VERSION_CONFLICT");
        }
        if (row.isDefault()) {
            mapper.promoteFallbackDefault(userId);
        }
        mapper.queueNotification(userId, "PAYMENT_METHOD_UNBOUND:" + methodId + ":" + key,
                "支付方式已解绑", "尾号 " + row.last4() + " 的支付方式已从 Nexion 账户解绑，不再用于后续扣款。",
                "/pages/me/wallet/cards");
        audit("USER_PAYMENT_METHOD_UNBIND", "USER_PAYMENT_METHOD", String.valueOf(methodId), userId, request, key,
                Map.of("last4", row.last4(), "provider", row.provider(), "providerRevocation", "NOT_REQUIRED"));
        return Map.of("id", methodId, "status", "UNBOUND", "providerRevocation", "NOT_REQUIRED");
    }

    private Map<String, Object> doNotifyRebind(Long userId, Long methodId, String key,
                                               UserPaymentMethodCommandRequest request) {
        UserPaymentMethodMapper.PaymentMethodRow row = requireMethod(userId, methodId);
        if (!row.trialGuard() || !"BOUND".equals(row.status())) {
            throw new BizException(OpsErrorCode.INVALID_STATE_TRANSITION.httpStatus(), "PAYMENT_METHOD_REBIND_NOTICE_NOT_ALLOWED");
        }
        if (mapper.markRebindNotified(userId, methodId, request.expectedVersion()) != 1) {
            throw new BizException(OpsErrorCode.INVALID_STATE_TRANSITION.httpStatus(), "PAYMENT_METHOD_VERSION_CONFLICT");
        }
        mapper.queueNotification(userId, "PAYMENT_METHOD_REBIND:" + methodId + ":" + key,
                "请更换试用扣款支付方式", "尾号 " + row.last4() + " 的支付方式正在被试用会话占用，请先完成换绑。",
                "/pages/me/wallet/cards/new");
        audit("USER_PAYMENT_METHOD_REBIND_NOTICE", "USER_PAYMENT_METHOD", String.valueOf(methodId), userId, request, key,
                Map.of("trialRefId", row.trialRefId() == null ? "" : row.trialRefId(), "last4", row.last4(),
                        "notification", "QUEUED"));
        return Map.of("id", methodId, "status", "NOTIFIED", "notification", "QUEUED");
    }

    private void validateCommand(String key, UserPaymentMethodCommandRequest request, boolean versionRequired) {
        if (!StringUtils.hasText(key)) {
            throw new BizException(OpsErrorCode.IDEMPOTENCY_KEY_REQUIRED.httpStatus(), OpsErrorCode.IDEMPOTENCY_KEY_REQUIRED.name());
        }
        if (request == null || !StringUtils.hasText(request.reason()) || request.reason().trim().length() < 8) {
            throw new BizException(OpsErrorCode.VALIDATION_FAILED.httpStatus(), "OPERATION_REASON_TOO_SHORT");
        }
        if (request.reason().trim().length() > 200) {
            throw new BizException(OpsErrorCode.VALIDATION_FAILED.httpStatus(), "OPERATION_REASON_TOO_LONG");
        }
        if (versionRequired && request.expectedVersion() == null) {
            throw new BizException(OpsErrorCode.VALIDATION_FAILED.httpStatus(), "EXPECTED_VERSION_REQUIRED");
        }
    }

    private void requireUser(Long userId) {
        if (userId == null || !mapper.userExists(userId)) {
            throw new BizException(404, "USER_NOT_FOUND");
        }
    }

    private UserPaymentMethodMapper.PaymentMethodRow requireMethod(Long userId, Long methodId) {
        UserPaymentMethodMapper.PaymentMethodRow row = mapper.findMethod(userId, methodId);
        if (row == null) {
            throw new BizException(404, "PAYMENT_METHOD_NOT_FOUND");
        }
        return row;
    }

    private void audit(String action, String type, String resourceId, Long userId,
                       UserPaymentMethodCommandRequest request, String key, Map<String, Object> extra) {
        Map<String, Object> detail = new LinkedHashMap<>(extra);
        detail.put("reason", request.reason().trim());
        detail.put("idempotencyKey", key);
        audit.recordRequired(AuditLogWriteRequest.builder().action(action).resourceType(type).resourceId(resourceId)
                .userId(userId).actorUsername(operator(request)).riskLevel("HIGH").detail(detail).build());
    }

    private String operator(UserPaymentMethodCommandRequest request) {
        String resolved = AdminActorResolver.resolve(request.operator());
        return StringUtils.hasText(resolved) ? resolved.trim() : "system";
    }

    private String hash(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException(ex);
        }
    }
}
