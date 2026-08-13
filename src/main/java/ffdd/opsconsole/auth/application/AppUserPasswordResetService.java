package ffdd.opsconsole.auth.application;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import ffdd.opsconsole.auth.dto.UserOtpLoginChallengeResponse;
import ffdd.opsconsole.auth.dto.UserOtpLoginRequest;
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
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.LocalDate;
import java.time.LocalDateTime;
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
    private static final int OTP_TTL_MINUTES = 5;
    private static final int OTP_COOLDOWN_SECONDS = 60;
    private static final int OTP_WINDOW_MINUTES = 15;
    private static final int OTP_WINDOW_LIMIT = 5;
    private static final int OTP_DAY_LIMIT = 10;
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

    @Transactional
    public ApiResult<UserOtpLoginChallengeResponse> send(
            UserOtpLoginRequest request, String clientAddress) {
        if (request == null || !validCountryCode(request.countryCode()) || !validPhone(request.phone())) {
            return ApiResult.fail(422, "USER_PASSWORD_RESET_REQUEST_INVALID");
        }
        UserAuthEnvironment audience = UserAuthEnvironment.resolve(environment).orElse(null);
        if (audience == null) return ApiResult.fail(503, "USER_AUTH_ENVIRONMENT_FORBIDDEN");
        if (!otpDeliveryService.available()) return ApiResult.fail(503, "USER_OTP_DELIVERY_UNAVAILABLE");
        String countryCode = normalizeCountryCode(request.countryCode());
        String phone = request.phone().trim();
        if (!consumeOtpSendRate(resetKey(audience, countryCode, phone), LocalDateTime.now())) {
            return ApiResult.fail(429, "USER_OTP_SEND_RATE_LIMITED");
        }
        UserEntity user = findUser(audience, countryCode, phone);
        String challengeNo = "RESET-" + UUID.randomUUID().toString().replace("-", "");
        if (!eligible(user)) {
            return ApiResult.ok(new UserOtpLoginChallengeResponse(challengeNo, OTP_COOLDOWN_SECONDS, maskPhone(phone)));
        }
        String code = otpDeliveryService.verificationCode();
        userMapper.invalidateOpenPasswordResetChallenges(user.getId());
        if (userMapper.createLoginOtpChallenge(user.getId(), challengeNo, code, OTP_TTL_MINUTES) != 1) {
            throw new IllegalStateException("USER_PASSWORD_RESET_CHALLENGE_CREATE_FAILED");
        }
        try {
            otpDeliveryService.deliver(countryCode, phone, challengeNo, code, OTP_TTL_MINUTES);
        } catch (RuntimeException exception) {
            userMapper.invalidateOpenPasswordResetChallenges(user.getId());
            return ApiResult.ok(new UserOtpLoginChallengeResponse(
                    "RESET-" + UUID.randomUUID().toString().replace("-", ""),
                    OTP_COOLDOWN_SECONDS, maskPhone(phone)));
        }
        return ApiResult.ok(new UserOtpLoginChallengeResponse(
                challengeNo, OTP_COOLDOWN_SECONDS, maskPhone(phone)));
    }

    @Transactional
    public ApiResult<Map<String, Object>> complete(
            UserPasswordResetOtpCompleteRequest request, String clientAddress) {
        if (request == null || !validCountryCode(request.countryCode()) || !validPhone(request.phone())
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
        if (!eligible(user)
                || userMapper.consumeValidLoginOtp(
                        user.getId(), request.challengeNo().trim(), request.code().trim()) != 1) {
            if (user != null) userMapper.recordInvalidLoginOtpAttempt(user.getId(), request.challengeNo().trim());
            return ApiResult.fail(422, "USER_PASSWORD_RESET_CHALLENGE_INVALID");
        }
        String currentHash = securityMapper.passwordHashForUpdate(user.getId());
        if (safeMatches(request.newPassword(), currentHash)) {
            return ApiResult.fail(422, "USER_NEW_PASSWORD_MUST_DIFFER");
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

    private boolean consumeOtpSendRate(String key, LocalDateTime now) {
        loginGuardMapper.initializeOtpSendGuard(key, now);
        UserOtpSendGuardRecord guard = loginGuardMapper.lockOtpSendGuard(key);
        if (guard == null) throw new IllegalStateException("USER_OTP_SEND_GUARD_UNAVAILABLE");
        if (guard.getLastSentAt() != null && guard.getLastSentAt().plusSeconds(OTP_COOLDOWN_SECONDS).isAfter(now)) {
            return false;
        }
        boolean newWindow = guard.getWindowStartedAt() == null
                || !guard.getWindowStartedAt().plusMinutes(OTP_WINDOW_MINUTES).isAfter(now);
        boolean newDay = guard.getDayStartedAt() == null || !guard.getDayStartedAt().equals(now.toLocalDate());
        int windowCount = newWindow ? 1 : guard.getWindowSendCount() + 1;
        int dayCount = newDay ? 1 : guard.getDaySendCount() + 1;
        if (windowCount > OTP_WINDOW_LIMIT || dayCount > OTP_DAY_LIMIT) return false;
        LocalDateTime windowStartedAt = newWindow ? now : guard.getWindowStartedAt();
        LocalDate dayStartedAt = newDay ? now.toLocalDate() : guard.getDayStartedAt();
        return loginGuardMapper.recordOtpSend(
                key, now, windowStartedAt, windowCount, dayStartedAt, dayCount) == 1;
    }

    private String resetKey(UserAuthEnvironment audience, String countryCode, String phone) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(
                    ("PASSWORD_RESET:" + audience.name() + ':' + countryCode + ':' + phone)
                            .getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 unavailable", exception);
        }
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
        return StringUtils.hasText(value) && normalizeCountryCode(value).matches("\\+[0-9]{1,4}");
    }

    private boolean validPhone(String value) {
        return StringUtils.hasText(value) && value.trim().matches("[0-9]{6,15}");
    }

    private String normalizeCountryCode(String value) {
        String normalized = value == null ? "" : value.trim().replace(" ", "");
        return normalized.startsWith("+") ? normalized : "+" + normalized;
    }

    private String maskPhone(String phone) {
        return phone.length() <= 4 ? "****" : "****" + phone.substring(phone.length() - 4);
    }
}
