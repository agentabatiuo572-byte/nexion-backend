package ffdd.opsconsole.finance.application;

import ffdd.opsconsole.auth.application.UserOtpDeliveryService;
import ffdd.opsconsole.finance.mapper.AppPayoutAddressMapper;
import ffdd.opsconsole.finance.mapper.AppPayoutAddressMapper.PayoutAddressRow;
import ffdd.opsconsole.finance.mapper.AppPayoutAddressMapper.UserContact;
import ffdd.opsconsole.shared.api.ApiResult;
import ffdd.opsconsole.shared.audit.AuditLogService;
import ffdd.opsconsole.shared.audit.AuditLogWriteRequest;
import ffdd.opsconsole.shared.exception.BizException;
import ffdd.opsconsole.shared.idempotency.AdminIdempotencyService;
import ffdd.opsconsole.shared.security.SupportedUserPhonePolicy;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.LocalDateTime;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

@Service
@RequiredArgsConstructor
public class AppPayoutAddressService {
    private static final Set<String> NETWORKS = Set.of("USDT-TRC20", "USDT-BEP20", "USDT-ERC20");
    private static final int CHANGE_COOLDOWN_DAYS = 7;
    private static final int EFFECTIVE_DELAY_HOURS = 24;
    private final AppPayoutAddressMapper mapper;
    private final UserOtpDeliveryService otpDelivery;
    private final AuditLogService audit;
    private final AdminIdempotencyService idempotency;
    private final PayoutAddressOtpAttemptService otpAttempts;
    /** Null is accepted only for legacy production-only unit fixtures. */
    private final FundsSandboxProfileGuard sandboxProfile;
    /** Null is accepted only for legacy production-only unit fixtures. */
    private final FundsSandboxRunScope sandboxRun;

    @Transactional(readOnly = true)
    public ApiResult<Map<String, Object>> list(Long userId) {
        Scope scope = scope();
        requireUser(userId, scope);
        List<Map<String, Object>> rows = (scope.sandbox()
                ? mapper.sandboxList(scope.runId(), userId)
                : mapper.list(userId)).stream().map(row -> view(row, scope)).toList();
        return ApiResult.ok(linked("addresses", rows, "serverCanonical", true,
                "changeCooldownDays", CHANGE_COOLDOWN_DAYS,
                "effectiveDelayHours", EFFECTIVE_DELAY_HOURS,
                "inFlightWithdrawalBlocked", true,
                "source", scope.source(), "sourceEnvironment", scope.sourceEnvironment(), "runId", scope.runId()));
    }

    @Transactional
    public ApiResult<Map<String, Object>> sendOtp(Long userId) {
        Scope scope = scope();
        requireActiveUserLock(userId, scope);
        UserContact contact = scope.sandbox() ? mapper.sandboxUserContact(userId) : mapper.userContact(userId);
        if (contact == null || !SupportedUserPhonePolicy.isSupportedDestination(
                contact.countryCode(), contact.phone())) {
            throw new BizException(422, "PAYOUT_ADDRESS_PHONE_INVALID");
        }
        if (!otpDelivery.available(contact.countryCode())) {
            throw new BizException(503, "PAYOUT_ADDRESS_OTP_DELIVERY_UNAVAILABLE");
        }
        if ((scope.sandbox() ? mapper.recentSandboxOtpCount(scope.runId(), userId) : mapper.recentOtpCount(userId)) > 0) {
            throw new BizException(429, "PAYOUT_ADDRESS_OTP_COOLDOWN");
        }
        if ((scope.sandbox() ? mapper.todaySandboxOtpCount(scope.runId(), userId) : mapper.todayOtpCount(userId)) >= 10) {
            throw new BizException(429, "PAYOUT_ADDRESS_OTP_DAILY_LIMIT");
        }
        String challengeNo = "PAYOUT-" + UUID.randomUUID().toString().replace("-", "").toUpperCase(Locale.ROOT);
        String code = otpDelivery.verificationCode(contact.countryCode());
        int inserted = scope.sandbox()
                ? mapper.insertSandboxOtp(scope.runId(), userId, challengeNo, code)
                : mapper.insertOtp(userId, challengeNo, code);
        if (inserted != 1) throw new BizException(409, "PAYOUT_ADDRESS_OTP_CONFLICT");
        otpDelivery.deliver(contact.countryCode(), contact.phone(), challengeNo, code, 5);
        return ApiResult.ok(linked("challengeNo", challengeNo, "expiresInSeconds", 300,
                "source", scope.source(), "sourceEnvironment", scope.sourceEnvironment(),
                "runId", scope.runId(), "serverCanonical", true));
    }

    @Transactional
    public ApiResult<Map<String, Object>> save(Long userId, SaveRequest request, String idempotencyKey) {
        Scope scope = scope();
        requireUser(userId, scope);
        if (request == null) throw new BizException(422, "PAYOUT_ADDRESS_REQUEST_REQUIRED");
        String network = normalizeNetwork(request.network());
        String address = normalizeAddress(network, request.address());
        String challenge = StringUtils.hasText(request.challengeNo()) ? request.challengeNo().trim() : "";
        String code = StringUtils.hasText(request.code()) ? request.code().trim() : "";
        if (!challenge.startsWith("PAYOUT-") || !code.matches("\\d{6}")) {
            throw new BizException(422, "PAYOUT_ADDRESS_OTP_INVALID");
        }
        String requestHash = hash(scope.sourceEnvironment() + "|" + scope.runId() + "|" + userId + "|"
                + network + "|" + address + "|" + challenge + "|" + code);
        String operation = scope.sandbox()
                ? "USER_PAYOUT_ADDRESS:SANDBOX:" + scope.runId() + ":" + userId
                : "USER_PAYOUT_ADDRESS:" + userId;
        return (ApiResult<Map<String, Object>>) (ApiResult) idempotency.execute(
                operation,
                idempotencyKey, requestHash, ApiResult.class,
                () -> saveOnce(userId, network, address, challenge, code, scope));
    }

    private ApiResult<Map<String, Object>> saveOnce(
            Long userId, String network, String address, String challenge, String code, Scope scope) {
        boolean otpValid = scope.sandbox()
                ? otpAttempts.verifyAndConsumeSandbox(scope.runId(), userId, challenge, code)
                : otpAttempts.verifyAndConsume(userId, challenge, code);
        if (!otpValid) {
            throw new BizException(422, "PAYOUT_ADDRESS_OTP_INVALID");
        }
        int unsettled = scope.sandbox()
                ? mapper.unsettledSandboxWithdrawalCount(scope.runId(), userId)
                : mapper.unsettledWithdrawalCount(userId);
        if (unsettled > 0) {
            throw new BizException(409, "PAYOUT_ADDRESS_CHANGE_BLOCKED_BY_WITHDRAWAL");
        }
        PayoutAddressRow current = scope.sandbox()
                ? mapper.sandboxLock(scope.runId(), userId, network)
                : mapper.lock(userId, network);
        if (current != null && current.nextChangeAllowedAt() != null
                && current.nextChangeAllowedAt().isAfter(LocalDateTime.now())) {
            throw new BizException(409, "PAYOUT_ADDRESS_CHANGE_COOLDOWN");
        }
        if (current != null && addressEquals(network, address, current.address())) return ApiResult.ok(view(current, scope));
        int changed = current == null
                ? (scope.sandbox() ? mapper.sandboxInsert(scope.runId(), userId, network, address) : mapper.insert(userId, network, address))
                : (scope.sandbox() ? mapper.sandboxUpdate(scope.runId(), userId, network, address, current.version())
                        : mapper.update(userId, network, address, current.version()));
        if (changed != 1) throw new BizException(409, "PAYOUT_ADDRESS_STATE_CONFLICT");
        if (scope.sandbox()) {
            mapper.sandboxInsertHistory(scope.runId(), userId, network, current == null ? null : current.address(), address,
                    current == null ? "CREATED" : "CHANGED");
        } else {
            mapper.insertHistory(userId, network, current == null ? null : current.address(), address,
                    current == null ? "CREATED" : "CHANGED");
        }
        PayoutAddressRow saved = scope.sandbox()
                ? mapper.sandboxLock(scope.runId(), userId, network)
                : mapper.lock(userId, network);
        if (saved == null) throw new BizException(409, "PAYOUT_ADDRESS_STATE_CONFLICT");
        audit.recordRequired(AuditLogWriteRequest.builder()
                .action(current == null ? "PAYOUT_ADDRESS_CREATED" : "PAYOUT_ADDRESS_CHANGED")
                .resourceType("PAYOUT_ADDRESS").resourceId(userId + ":" + network)
                .userId(userId).actorId(userId).actorType("USER").actorUsername("user:" + userId)
                .riskLevel("HIGH").result("SUCCESS")
                .detail(linked("network", network, "addressMasked", mask(address),
                        "effectiveAt", saved.effectiveAt(), "nextChangeAllowedAt", saved.nextChangeAllowedAt()))
                .build());
        return ApiResult.ok(view(saved, scope));
    }

    private void requireUser(Long userId, Scope scope) {
        if (userId == null) throw new BizException(401, "USER_AUTH_REQUIRED");
        Long active = scope.sandbox() ? mapper.activeSandboxUser(userId) : mapper.activeUser(userId);
        if (active == null) throw new BizException(401, "USER_AUTH_REQUIRED");
        boolean sandboxUser = Integer.valueOf(1).equals(mapper.isSandboxUser(userId));
        if (scope.development() && !sandboxUser) {
            throw new BizException(403, "PAYOUT_ADDRESS_DEVELOPMENT_USER_REQUIRED");
        }
        if (!scope.sandbox() && !scope.development() && sandboxUser) {
            throw new BizException(403, "PAYOUT_ADDRESS_SANDBOX_USER_FORBIDDEN");
        }
    }

    private void requireActiveUserLock(Long userId, Scope scope) {
        if (userId == null) throw new BizException(401, "USER_AUTH_REQUIRED");
        Long active = scope.sandbox() ? mapper.lockActiveSandboxUser(userId) : mapper.lockActiveUser(userId);
        if (active == null) {
            throw new BizException(401, "USER_AUTH_REQUIRED");
        }
        boolean sandboxUser = Integer.valueOf(1).equals(mapper.isSandboxUser(userId));
        if (scope.development() && !sandboxUser) {
            throw new BizException(403, "PAYOUT_ADDRESS_DEVELOPMENT_USER_REQUIRED");
        }
        if (!scope.sandbox() && !scope.development() && sandboxUser) {
            throw new BizException(403, "PAYOUT_ADDRESS_SANDBOX_USER_FORBIDDEN");
        }
    }

    private String normalizeNetwork(String value) {
        String network = value == null ? "" : value.trim().toUpperCase(Locale.ROOT);
        if (!NETWORKS.contains(network)) throw new BizException(422, "PAYOUT_ADDRESS_NETWORK_INVALID");
        return network;
    }

    private String normalizeAddress(String network, String value) {
        String address = value == null ? "" : value.trim();
        boolean valid = "USDT-TRC20".equals(network)
                ? address.matches("^T[1-9A-HJ-NP-Za-km-z]{33}$")
                : address.matches("^0x[0-9a-fA-F]{40}$");
        if (!valid) throw new BizException(422, "PAYOUT_ADDRESS_FORMAT_INVALID");
        return address;
    }

    private boolean addressEquals(String network, String left, String right) {
        return "USDT-TRC20".equals(network) ? left.equals(right) : left.equalsIgnoreCase(right);
    }

    private Map<String, Object> view(PayoutAddressRow row, Scope scope) {
        return linked("network", row.network(), "address", row.address(), "status", row.status(),
                "effectiveAt", row.effectiveAt(), "createdAt", row.createdAt(),
                "nextChangeAllowedAt", row.nextChangeAllowedAt(),
                "changePending", row.effectiveAt() != null && row.effectiveAt().isAfter(LocalDateTime.now()),
                "source", scope.source(), "sourceEnvironment", scope.sourceEnvironment(),
                "runId", scope.runId(), "serverCanonical", true);
    }

    private Scope scope() {
        if (sandboxProfile != null && sandboxProfile.isLocalSandboxEnabled()) {
            if (sandboxRun == null) throw new BizException(503, "PAYOUT_ADDRESS_SANDBOX_RUN_ID_REQUIRED");
            return new Scope("mock", "SANDBOX", sandboxRun.requireRunId(), true, false);
        }
        if (sandboxProfile != null) {
            if (sandboxProfile.isStrictIsolatedRuntime()) {
                throw new BizException(503, "PAYOUT_ADDRESS_SANDBOX_UNAVAILABLE");
            }
            if (!sandboxProfile.isStrictProductionRuntime()) {
                throw new BizException(503, "PAYOUT_ADDRESS_PROFILE_INVALID");
            }
        }
        return new Scope("server", "PRODUCTION", "", false, false);
    }

    private String mask(String value) {
        return value.length() <= 12 ? "***" : value.substring(0, 6) + "..." + value.substring(value.length() - 4);
    }

    private String hash(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException("SHA-256 unavailable", ex);
        }
    }

    private Map<String, Object> linked(Object... values) {
        Map<String, Object> result = new LinkedHashMap<>();
        for (int i = 0; i < values.length; i += 2) result.put(String.valueOf(values[i]), values[i + 1]);
        return result;
    }

    public record SaveRequest(String network, String address, String challengeNo, String code) { }

    private record Scope(String source, String sourceEnvironment, String runId, boolean sandbox, boolean development) { }
}
