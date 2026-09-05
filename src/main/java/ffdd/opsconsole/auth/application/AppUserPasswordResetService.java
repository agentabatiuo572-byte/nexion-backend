package ffdd.opsconsole.auth.application;

import ffdd.opsconsole.platform.facade.PlatformConfigFacade;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import ffdd.opsconsole.auth.dto.UserOtpLoginChallengeResponse;
import ffdd.opsconsole.auth.captcha.CaptchaOtpGate;
import ffdd.opsconsole.auth.captcha.CaptchaScene;
import ffdd.opsconsole.auth.dto.UserOtpLoginRequest;
import ffdd.opsconsole.auth.dto.UserOtpLoginVerifyRequest;
import ffdd.opsconsole.auth.dto.UserPasswordResetOtpCompleteRequest;
import ffdd.opsconsole.auth.infrastructure.UserOtpSendGuardRecord;
import ffdd.opsconsole.auth.mapper.AppUserSecurityMapper;
import ffdd.opsconsole.auth.mapper.UserLoginGuardMapper;
import ffdd.opsconsole.shared.api.ApiResult;
import ffdd.opsconsole.shared.audit.AuditLogService;
import ffdd.opsconsole.shared.audit.AuditLogWriteRequest;
import ffdd.opsconsole.shared.outbox.EventOutboxService;
import ffdd.opsconsole.shared.security.UserAccountBlocklistVerifier;
import ffdd.opsconsole.shared.security.UserAuthEnvironment;
import ffdd.opsconsole.shared.security.mapper.AuthSessionMapper;
import ffdd.opsconsole.user.infrastructure.UserEntity;
import ffdd.opsconsole.user.mapper.UserOpsMapper;
import java.time.LocalDateTime;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.core.env.Environment;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

@Service
@RequiredArgsConstructor
public class AppUserPasswordResetService {
    private static final int OTP_WINDOW_MINUTES = 15;
    private static final int OTP_WINDOW_LIMIT = 5;
    private static final int CLIENT_WINDOW_MINUTES = 15;
    private static final int CLIENT_WINDOW_LIMIT = 10;
    private static final int CLIENT_24H_LIMIT = 100;
    private static final Set<String> WEAK_PASSWORDS = Set.of(
            "12345678", "123456789", "password", "password1", "passw0rd",
            "qwerty123", "admin123", "welcome1", "letmein1");

    private final UserOpsMapper userMapper;
    private final AppUserSecurityMapper securityMapper;
    private final AuthSessionMapper sessionMapper;
    private final UserLoginGuardMapper loginGuardMapper;
    private final PasswordEncoder passwordEncoder;
    private final UserAccountBlocklistVerifier blocklistVerifier;
    private final UserOtpDeliveryService otpDeliveryService;
    private final AuditLogService audit;
    private final EventOutboxService outbox;
    private final Environment environment;
    private final PlatformConfigFacade configFacade;
    private final CaptchaOtpGate captchaGate;

    @Transactional
    public ApiResult<UserOtpLoginChallengeResponse> send(
            UserOtpLoginRequest request, String clientAddress) {
        if (request == null || !validCountryCode(request.countryCode()) || !validPhone(request.countryCode(), request.phone())) {
            return ApiResult.fail(422, "USER_PASSWORD_RESET_REQUEST_INVALID");
        }
        UserAuthEnvironment audience = UserAuthEnvironment.resolve(environment).orElse(null);
        if (audience == null) return ApiResult.fail(503, "USER_AUTH_ENVIRONMENT_FORBIDDEN");
        String countryCode = normalizeCountryCode(request.countryCode());
        String phone = request.phone().trim();
        if (!otpDeliveryService.available(countryCode)) {
            return ApiResult.fail(503, "USER_OTP_DELIVERY_UNAVAILABLE");
        }
        AppOtpPolicy policy = AppOtpPolicy.load(configFacade);
        LocalDateTime now = LocalDateTime.now();
        if (!consumeClientRate(audience, clientAddress, now)) {
            return ApiResult.fail(429, "USER_PASSWORD_RESET_CLIENT_RATE_LIMITED");
        }
        OtpSendRateDecision otpRate = consumeOtpSendRate(resetKey(audience, countryCode, phone), now, policy,
                request.captchaTicket(), clientAddress);
        if (otpRate != OtpSendRateDecision.ALLOWED) {
            return otpRate == OtpSendRateDecision.CAPTCHA_UNAVAILABLE
                    ? ApiResult.fail(503, "USER_CAPTCHA_VERIFIER_UNAVAILABLE")
                    : otpRate == OtpSendRateDecision.CAPTCHA_REJECTED
                    ? ApiResult.fail(428, "USER_CAPTCHA_REQUIRED")
                    : ApiResult.fail(429, "USER_OTP_SEND_RATE_LIMITED");
        }
        UserEntity user = findUser(audience, countryCode, phone);
        String challengeNo = "RESET-" + UUID.randomUUID().toString().replace("-", "");
        if (!eligible(user)) {
            return ApiResult.ok(new UserOtpLoginChallengeResponse(challengeNo, policy.cooldownSeconds(), maskPhone(phone)));
        }
        String code = otpDeliveryService.verificationCode(countryCode);
        userMapper.invalidateOpenPasswordResetChallenges(user.getId());
        if (userMapper.createLoginOtpChallenge(user.getId(), challengeNo, code, policy.ttlMinutes()) != 1) {
            throw new IllegalStateException("USER_PASSWORD_RESET_CHALLENGE_CREATE_FAILED");
        }
        try {
            otpDeliveryService.deliver(countryCode, phone, challengeNo, code, policy.ttlMinutes());
        } catch (RuntimeException exception) {
            userMapper.invalidateOpenPasswordResetChallenges(user.getId());
            return ApiResult.fail(503, "USER_OTP_DELIVERY_FAILED");
        }
        return ApiResult.ok(new UserOtpLoginChallengeResponse(
                challengeNo, policy.cooldownSeconds(), maskPhone(phone)));
    }

    @Transactional
    public ApiResult<Map<String, Object>> verify(UserOtpLoginVerifyRequest request) {
        if (request == null || !validCountryCode(request.countryCode()) || !validPhone(request.countryCode(), request.phone())
                || !StringUtils.hasText(request.challengeNo())
                || !request.challengeNo().trim().matches("RESET-[a-f0-9]{32}")
                || !StringUtils.hasText(request.code()) || !request.code().trim().matches("\\d{6}")) {
            return ApiResult.fail(422, "USER_PASSWORD_RESET_CHALLENGE_INVALID");
        }
        UserAuthEnvironment audience = UserAuthEnvironment.resolve(environment).orElse(null);
        if (audience == null) return ApiResult.fail(503, "USER_AUTH_ENVIRONMENT_FORBIDDEN");
        String countryCode = normalizeCountryCode(request.countryCode());
        String phone = request.phone().trim();
        UserEntity user = findUser(audience, countryCode, phone);
        String challengeNo = request.challengeNo().trim();
        String code = request.code().trim();
        if (!eligible(user) || userMapper.countValidLoginOtp(user.getId(), challengeNo, code) != 1) {
            if (user != null) userMapper.recordInvalidLoginOtpAttempt(user.getId(), challengeNo);
            return ApiResult.fail(422, "USER_PASSWORD_RESET_CHALLENGE_INVALID");
        }
        return ApiResult.ok(Map.of("status", "PASSWORD_RESET_OTP_VERIFIED"));
    }

    @Transactional
    public ApiResult<Map<String, Object>> complete(
            UserPasswordResetOtpCompleteRequest request, String clientAddress) {
        if (request == null || !validCountryCode(request.countryCode()) || !validPhone(request.countryCode(), request.phone())
                || !StringUtils.hasText(request.challengeNo())
                || !request.challengeNo().trim().matches("RESET-[a-f0-9]{32}")
                || !StringUtils.hasText(request.code()) || !request.code().trim().matches("\\d{6}")
                || !strongPassword(request.newPassword())) {
            return ApiResult.fail(422, "USER_PASSWORD_RESET_CHALLENGE_INVALID");
        }
        UserAuthEnvironment audience = UserAuthEnvironment.resolve(environment).orElse(null);
        if (audience == null) return ApiResult.fail(503, "USER_AUTH_ENVIRONMENT_FORBIDDEN");
        String countryCode = normalizeCountryCode(request.countryCode());
        String phone = request.phone().trim();
        UserEntity user = findUser(audience, countryCode, phone);
        String challengeNo = request.challengeNo().trim();
        String code = request.code().trim();
        if (!eligible(user)
                || userMapper.countValidLoginOtp(user.getId(), challengeNo, code) != 1) {
            if (user != null) userMapper.recordInvalidLoginOtpAttempt(user.getId(), request.challengeNo().trim());
            return ApiResult.fail(422, "USER_PASSWORD_RESET_CHALLENGE_INVALID");
        }
        String currentHash = securityMapper.passwordHashForUpdate(user.getId());
        if (safeMatches(request.newPassword(), currentHash)) {
            return ApiResult.fail(422, "USER_NEW_PASSWORD_MUST_DIFFER");
        }
        // Validate first, compare the new password, and consume only when the
        // password is actually eligible to change. The final atomic UPDATE is
        // still the race/replay fence if another request consumed it meanwhile.
        if (userMapper.consumeValidLoginOtp(user.getId(), challengeNo, code) != 1) {
            return ApiResult.fail(422, "USER_PASSWORD_RESET_CHALLENGE_INVALID");
        }
        if (securityMapper.updatePasswordHash(user.getId(), passwordEncoder.encode(request.newPassword())) != 1
                || securityMapper.markPasswordChanged(user.getId()) < 1) {
            throw new IllegalStateException("USER_PASSWORD_RESET_STATE_CHANGED");
        }
        int revoked = sessionMapper.revokeAllUserSessions(user.getId());
        audit.recordRequired(AuditLogWriteRequest.builder()
                .action("USER_PASSWORD_RESET_BY_OTP")
                .resourceType("USER_SECURITY")
                .resourceId(String.valueOf(user.getId()))
                .userId(user.getId())
                .actorId(user.getId())
                .actorType("USER")
                .actorUsername("user:" + user.getId())
                .result("SUCCESS")
                .riskLevel("HIGH")
                .detail(Map.of("revokedSessionCount", revoked, "channel", "OTP"))
                .build());
        outbox.publish("USER_SECURITY", String.valueOf(user.getId()), "auth.password_reset_completed",
                Map.of("userId", user.getId(), "revokedSessionCount", revoked));
        return ApiResult.ok(Map.of(
                "status", "PASSWORD_RESET",
                "revokedSessionCount", revoked));
    }

    private UserEntity findUser(UserAuthEnvironment audience, String countryCode, String phone) {
        return userMapper.selectOne(new LambdaQueryWrapper<UserEntity>()
                .in(UserEntity::getCountryCode, countryCode, countryCode.substring(1))
                .eq(UserEntity::getPhone, phone)
                .eq(UserEntity::getSandbox, audience == UserAuthEnvironment.SANDBOX ? 1 : 0)
                .eq(UserEntity::getIsDeleted, 0)
                .last("LIMIT 1"));
    }

    private boolean eligible(UserEntity user) {
        return user != null && "ACTIVE".equalsIgnoreCase(user.getStatus())
                && !blocklistVerifier.isBlocked(user.getId())
                && UserAuthEnvironmentPolicy.evaluate(environment, user) == UserAuthEnvironmentPolicy.Decision.ALLOW;
    }

    private OtpSendRateDecision consumeOtpSendRate(String key, LocalDateTime now, AppOtpPolicy policy,
            String captchaTicket, String clientAddress) {
        loginGuardMapper.initializeOtpSendGuard(key, now);
        UserOtpSendGuardRecord guard = loginGuardMapper.lockOtpSendGuard(key);
        if (guard == null) throw new IllegalStateException("USER_OTP_SEND_GUARD_UNAVAILABLE");
        if (guard.getLastSentAt() != null && guard.getLastSentAt().plusSeconds(policy.cooldownSeconds()).isAfter(now)) {
            return OtpSendRateDecision.RATE_LIMITED;
        }
        boolean newWindow = guard.getWindowStartedAt() == null
                || !guard.getWindowStartedAt().plusMinutes(OTP_WINDOW_MINUTES).isAfter(now);
        int windowCount = newWindow ? 1 : guard.getWindowSendCount() + 1;
        int legacyCount = guard.getLegacyWindowUntil() != null && guard.getLegacyWindowUntil().isAfter(now)
                ? guard.getDaySendCount() : 0;
        int trailing24hCount = loginGuardMapper.countRecentOtpSendEvents(key, now.minusHours(24));
        if (windowCount > OTP_WINDOW_LIMIT || legacyCount + trailing24hCount >= policy.max24h()) return OtpSendRateDecision.RATE_LIMITED;
        CaptchaOtpGate.Decision captcha = captchaGate.checkAndConsume(
                CaptchaScene.RESET, captchaTicket, clientAddress, trailing24hCount);
        if (!captcha.allowed()) return "USER_CAPTCHA_VERIFIER_UNAVAILABLE".equals(captcha.code())
                ? OtpSendRateDecision.CAPTCHA_UNAVAILABLE : OtpSendRateDecision.CAPTCHA_REJECTED;
        if (loginGuardMapper.insertOtpSendEvent(key, now) != 1) {
            throw new IllegalStateException("USER_OTP_SEND_EVENT_CREATE_FAILED");
        }
        LocalDateTime windowStartedAt = newWindow ? now : guard.getWindowStartedAt();
        LocalDateTime dayStartedAt = now;
        return loginGuardMapper.recordOtpSend(
                key, now, windowStartedAt, windowCount, dayStartedAt, trailing24hCount + 1) == 1
                ? OtpSendRateDecision.ALLOWED : OtpSendRateDecision.RATE_LIMITED;
    }

    private enum OtpSendRateDecision { ALLOWED, RATE_LIMITED, CAPTCHA_REJECTED, CAPTCHA_UNAVAILABLE }

    /**
     * A public recovery endpoint needs a caller quota in addition to the shared
     * destination quota. The client key is purpose-scoped and hashed, so raw
     * network addresses are never persisted in the OTP guard tables.
     */
    private boolean consumeClientRate(
            UserAuthEnvironment audience, String clientAddress, LocalDateTime now) {
        String key = clientRateKey(audience, clientAddress);
        loginGuardMapper.initializeOtpSendGuard(key, now);
        UserOtpSendGuardRecord guard = loginGuardMapper.lockOtpSendGuard(key);
        if (guard == null) throw new IllegalStateException("USER_PASSWORD_RESET_CLIENT_GUARD_UNAVAILABLE");
        boolean newWindow = guard.getWindowStartedAt() == null
                || !guard.getWindowStartedAt().plusMinutes(CLIENT_WINDOW_MINUTES).isAfter(now);
        int windowCount = newWindow ? 1 : guard.getWindowSendCount() + 1;
        int trailing24hCount = loginGuardMapper.countRecentOtpSendEvents(key, now.minusHours(24));
        if (windowCount > CLIENT_WINDOW_LIMIT || trailing24hCount >= CLIENT_24H_LIMIT) return false;
        if (loginGuardMapper.insertOtpSendEvent(key, now) != 1) {
            throw new IllegalStateException("USER_PASSWORD_RESET_CLIENT_EVENT_CREATE_FAILED");
        }
        LocalDateTime windowStartedAt = newWindow ? now : guard.getWindowStartedAt();
        return loginGuardMapper.recordOtpSend(
                key, now, windowStartedAt, windowCount, now, trailing24hCount + 1) == 1;
    }

    private String clientRateKey(UserAuthEnvironment audience, String clientAddress) {
        String normalized = StringUtils.hasText(clientAddress) ? clientAddress.trim() : "unknown";
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest((audience.name() + ":password-reset-client:" + normalized)
                            .getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 unavailable", exception);
        }
    }

    private String resetKey(UserAuthEnvironment audience, String countryCode, String phone) {
        return OtpPhoneRateLimitKey.from(audience.name(), countryCode, phone);
    }

    private boolean strongPassword(String password) {
        if (!StringUtils.hasText(password) || password.length() < 12 || password.length() > 72) return false;
        String lower = password.toLowerCase(Locale.ROOT);
        return !WEAK_PASSWORDS.contains(lower)
                && password.matches(".*[a-z].*") && password.matches(".*[A-Z].*")
                && password.matches(".*\\d.*") && password.matches(".*[^A-Za-z0-9].*");
    }

    private boolean safeMatches(String raw, String encoded) {
        try {
            return StringUtils.hasText(encoded) && passwordEncoder.matches(raw, encoded);
        } catch (RuntimeException ignored) {
            return false;
        }
    }

    private boolean validCountryCode(String value) {
        return StringUtils.hasText(value) && OtpPhoneCanonicalizer.isSupportedCountryCode(value);
    }

    private boolean validPhone(String countryCode, String value) {
        try {
            OtpPhoneCanonicalizer.toE164Digits(countryCode, value);
            return true;
        } catch (IllegalArgumentException ignored) {
            return false;
        }
    }

    private String normalizeCountryCode(String value) {
        String normalized = value == null ? "" : value.trim().replace(" ", "");
        return normalized.startsWith("+") ? normalized : "+" + normalized;
    }

    private String maskPhone(String phone) {
        return phone.length() <= 4 ? "****" : "****" + phone.substring(phone.length() - 4);
    }
}
