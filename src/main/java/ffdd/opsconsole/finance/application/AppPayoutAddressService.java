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
    private final AppPayoutAddressMapper mapper;
    private final UserOtpDeliveryService otpDelivery;
    private final AuditLogService audit;
    private final AdminIdempotencyService idempotency;
    private final PayoutAddressOtpAttemptService otpAttempts;

    @Transactional(readOnly = true)
    public ApiResult<Map<String, Object>> list(Long userId) {
        requireUser(userId);
        List<Map<String, Object>> rows = mapper.list(userId).stream().map(this::view).toList();
        return ApiResult.ok(linked("addresses", rows, "serverCanonical", true));
    }

    @Transactional
    public ApiResult<Map<String, Object>> sendOtp(Long userId) {
        requireActiveUserLock(userId);
        if (!otpDelivery.available()) throw new BizException(503, "PAYOUT_ADDRESS_OTP_DELIVERY_UNAVAILABLE");
        if (mapper.recentOtpCount(userId) > 0) throw new BizException(429, "PAYOUT_ADDRESS_OTP_COOLDOWN");
        if (mapper.todayOtpCount(userId) >= 10) throw new BizException(429, "PAYOUT_ADDRESS_OTP_DAILY_LIMIT");
        UserContact contact = mapper.userContact(userId);
        if (contact == null || !StringUtils.hasText(contact.countryCode()) || !StringUtils.hasText(contact.phone())) {
            throw new BizException(409, "PAYOUT_ADDRESS_PHONE_UNAVAILABLE");
        }
        String challengeNo = "PAYOUT-" + UUID.randomUUID().toString().replace("-", "").toUpperCase(Locale.ROOT);
        String code = otpDelivery.verificationCode();
        if (mapper.insertOtp(userId, challengeNo, code) != 1) throw new BizException(409, "PAYOUT_ADDRESS_OTP_CONFLICT");
        otpDelivery.deliver(contact.countryCode(), contact.phone(), challengeNo, code, 5);
        return ApiResult.ok(linked("challengeNo", challengeNo, "expiresInSeconds", 300));
    }

    public ApiResult<Map<String, Object>> save(Long userId, SaveRequest request, String idempotencyKey) {
        requireUser(userId);
        if (request == null) throw new BizException(422, "PAYOUT_ADDRESS_REQUEST_REQUIRED");
        String network = normalizeNetwork(request.network());
        String address = normalizeAddress(network, request.address());
        String challenge = StringUtils.hasText(request.challengeNo()) ? request.challengeNo().trim() : "";
        String code = StringUtils.hasText(request.code()) ? request.code().trim() : "";
        if (!challenge.startsWith("PAYOUT-") || !code.matches("\\d{6}")) {
            throw new BizException(422, "PAYOUT_ADDRESS_OTP_INVALID");
        }
        String requestHash = hash(userId + "|" + network + "|" + address + "|" + challenge + "|" + code);
        return (ApiResult<Map<String, Object>>) (ApiResult) idempotency.execute(
                "USER_PAYOUT_ADDRESS:" + userId, idempotencyKey, requestHash, ApiResult.class,
                () -> saveOnce(userId, network, address, challenge, code));
    }

    private ApiResult<Map<String, Object>> saveOnce(
            Long userId, String network, String address, String challenge, String code) {
        if (!otpAttempts.verifyAndConsume(userId, challenge, code)) {
            throw new BizException(422, "PAYOUT_ADDRESS_OTP_INVALID");
        }
        if (mapper.unsettledWithdrawalCount(userId) > 0) {
            throw new BizException(409, "PAYOUT_ADDRESS_CHANGE_BLOCKED_BY_WITHDRAWAL");
        }
        PayoutAddressRow current = mapper.lock(userId, network);
        if (current != null && current.nextChangeAllowedAt() != null
                && current.nextChangeAllowedAt().isAfter(LocalDateTime.now())) {
            throw new BizException(409, "PAYOUT_ADDRESS_CHANGE_COOLDOWN");
        }
        if (current != null && addressEquals(network, address, current.address())) return ApiResult.ok(view(current));
        int changed = current == null
                ? mapper.insert(userId, network, address)
                : mapper.update(userId, network, address, current.version());
        if (changed != 1) throw new BizException(409, "PAYOUT_ADDRESS_STATE_CONFLICT");
        mapper.insertHistory(userId, network, current == null ? null : current.address(), address,
                current == null ? "CREATED" : "CHANGED");
        PayoutAddressRow saved = mapper.lock(userId, network);
        audit.recordRequired(AuditLogWriteRequest.builder()
                .action(current == null ? "PAYOUT_ADDRESS_CREATED" : "PAYOUT_ADDRESS_CHANGED")
                .resourceType("PAYOUT_ADDRESS").resourceId(userId + ":" + network)
                .userId(userId).actorId(userId).actorType("USER").actorUsername("user:" + userId)
                .riskLevel("HIGH").result("SUCCESS")
                .detail(linked("network", network, "addressMasked", mask(address),
                        "effectiveAt", saved.effectiveAt(), "nextChangeAllowedAt", saved.nextChangeAllowedAt()))
                .build());
        return ApiResult.ok(view(saved));
    }

    private void requireUser(Long userId) {
        if (userId == null || mapper.activeUser(userId) == null) throw new BizException(401, "USER_AUTH_REQUIRED");
    }

    private void requireActiveUserLock(Long userId) {
        if (userId == null || mapper.lockActiveUser(userId) == null) {
            throw new BizException(401, "USER_AUTH_REQUIRED");
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

    private Map<String, Object> view(PayoutAddressRow row) {
        return linked("network", row.network(), "address", row.address(), "status", row.status(),
                "effectiveAt", row.effectiveAt(), "createdAt", row.createdAt(),
                "nextChangeAllowedAt", row.nextChangeAllowedAt(),
                "changePending", row.effectiveAt() != null && row.effectiveAt().isAfter(LocalDateTime.now()));
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
}
