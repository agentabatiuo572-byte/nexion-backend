package ffdd.opsconsole.auth.application;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import ffdd.opsconsole.auth.dto.UserLoginRequest;
import ffdd.opsconsole.auth.dto.UserLoginResponse;
import ffdd.opsconsole.auth.dto.UserPasswordResetCompleteRequest;
import ffdd.opsconsole.auth.dto.UserTwoFactorLoginRequest;
import ffdd.opsconsole.auth.dto.UserRefreshRequest;
import ffdd.opsconsole.auth.infrastructure.UserLoginGuardRecord;
import ffdd.opsconsole.auth.mapper.UserLoginGuardMapper;
import ffdd.opsconsole.shared.api.ApiResult;
import ffdd.opsconsole.platform.facade.PlatformConfigFacade;
import ffdd.opsconsole.shared.exception.BizException;
import ffdd.opsconsole.shared.security.JwtProperties;
import ffdd.opsconsole.shared.security.JwtTokenProvider;
import ffdd.opsconsole.shared.security.UserAccountBlocklistVerifier;
import ffdd.opsconsole.shared.security.infrastructure.UserSessionEntity;
import ffdd.opsconsole.shared.security.mapper.AuthSessionMapper;
import ffdd.opsconsole.shared.outbox.EventOutboxService;
import ffdd.opsconsole.user.infrastructure.UserEntity;
import ffdd.opsconsole.user.mapper.UserOpsMapper;
import java.time.LocalDateTime;
import java.time.Duration;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.util.HexFormat;
import java.util.Base64;
import java.util.List;
import java.util.UUID;
import java.util.Map;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

@Service
@RequiredArgsConstructor
public class AppUserAuthService {
    private static final int MAX_FAILURES = 5;
    private static final int WINDOW_MINUTES = 15;
    private static final int IP_ATTEMPTS_PER_MINUTE = 60;
    private static final String DUMMY_PASSWORD_HASH =
            "$2a$10$IPabDA.89TrSOBbFNdsPDejK6ip8ywMtoYds8SWtmjhSpN4sK9mFG";
    private final UserOpsMapper userMapper;
    private final AuthSessionMapper sessionMapper;
    private final UserLoginGuardMapper loginGuardMapper;
    private final PasswordEncoder passwordEncoder;
    private final JwtTokenProvider tokenProvider;
    private final JwtProperties jwtProperties;
    private final UserAccountBlocklistVerifier blocklistVerifier;
    private final PlatformConfigFacade configFacade;
    private final UserOtpDeliveryService otpDeliveryService;
    private final EventOutboxService outboxService;
    private final SecureRandom secureRandom = new SecureRandom();

    @PostConstruct
    void ensureLoginGuardSchema() {
        loginGuardMapper.createTable();
        if (loginGuardMapper.countUserIdColumn() == 0) loginGuardMapper.addUserIdColumn();
        if (loginGuardMapper.countUpdatedAtIndex() == 0) loginGuardMapper.addUpdatedAtIndex();
    }

    @Transactional
    public ApiResult<UserLoginResponse> login(UserLoginRequest request) {
        return login(request, "local");
    }

    @Transactional
    public ApiResult<UserLoginResponse> login(UserLoginRequest request, String clientAddress) {
        if (request == null || !validCountryCode(request.countryCode()) || !validPhone(request.phone())
                || !StringUtils.hasText(request.password())) {
            return invalidCredential();
        }
        LocalDateTime now = LocalDateTime.now();
        if (!consumeClientRate(clientAddress, now)) {
            return ApiResult.fail(429, "USER_LOGIN_RATE_LIMITED");
        }
        String countryCode = normalizeCountryCode(request.countryCode());
        String phone = request.phone().trim();
        String loginKey = loginKey(countryCode, phone);
        loginGuardMapper.initialize(loginKey, now);
        UserLoginGuardRecord guard = loginGuardMapper.lock(loginKey);
        UserEntity user = userMapper.selectOne(new LambdaQueryWrapper<UserEntity>()
                .in(UserEntity::getCountryCode, countryCode, countryCode.substring(1))
                .eq(UserEntity::getPhone, phone)
                .eq(UserEntity::getIsDeleted, 0)
                .last("LIMIT 1"));
        boolean allowlisted = user != null && blocklistVerifier.isAllowlisted(user.getId());
        if (user != null) {
            loginGuardMapper.bindUser(loginKey, user.getId());
        }
        if (!allowlisted && guard != null && guard.getLockedUntil() != null && guard.getLockedUntil().isAfter(now)) {
            return ApiResult.fail(429, "USER_LOGIN_TEMPORARILY_LOCKED");
        }
        boolean passwordMatches = safePasswordMatches(request.password(),
                user != null && StringUtils.hasText(user.getPasswordHash()) ? user.getPasswordHash() : DUMMY_PASSWORD_HASH);
        if (user == null || !"ACTIVE".equalsIgnoreCase(user.getStatus()) || !passwordMatches) {
            recordFailure(loginKey, user == null ? null : user.getId(), guard, now);
            return invalidCredential();
        }
        if (blocklistVerifier.isBlocked(user.getId())) {
            return ApiResult.fail(403, "ACCOUNT_BLOCKLISTED");
        }

        loginGuardMapper.clear(loginKey);
        userMapper.clearLoginFailure(user.getId());

        if (userMapper.isPasswordResetRequired(user.getId())) {
            return ApiResult.fail(428, "USER_PASSWORD_RESET_REQUIRED");
        }
        if (userMapper.isTwoFactorEnabled(user.getId())) {
            return issueTwoFactorChallenge(user, countryCode, phone);
        }

        return issueSession(user, countryCode, phone, clientAddress);
    }

    @Transactional
    public ApiResult<UserLoginResponse> completePasswordReset(UserPasswordResetCompleteRequest request) {
        return completePasswordReset(request, "local");
    }

    @Transactional
    public ApiResult<UserLoginResponse> completePasswordReset(
            UserPasswordResetCompleteRequest request, String clientAddress) {
        if (request == null || !validCountryCode(request.countryCode()) || !validPhone(request.phone())
                || !StringUtils.hasText(request.currentPassword()) || !strongPassword(request.newPassword())) {
            return ApiResult.fail(422, "USER_NEW_PASSWORD_POLICY_REJECTED");
        }
        LocalDateTime now = LocalDateTime.now();
        if (!consumeClientRate(clientAddress, now)) return ApiResult.fail(429, "USER_LOGIN_RATE_LIMITED");
        String countryCode = normalizeCountryCode(request.countryCode());
        String phone = request.phone().trim();
        String loginKey = loginKey(countryCode, phone);
        loginGuardMapper.initialize(loginKey, now);
        UserLoginGuardRecord guard = loginGuardMapper.lock(loginKey);
        if (guard != null && guard.getLockedUntil() != null && guard.getLockedUntil().isAfter(now)) {
            return ApiResult.fail(429, "USER_LOGIN_TEMPORARILY_LOCKED");
        }
        UserEntity user = findUser(countryCode, phone);
        if (user == null || !"ACTIVE".equalsIgnoreCase(user.getStatus())
                || !safePasswordMatches(request.currentPassword(), user.getPasswordHash())) {
            recordFailure(loginKey, user == null ? null : user.getId(), guard, now);
            return invalidCredential();
        }
        if (blocklistVerifier.isBlocked(user.getId())) {
            return ApiResult.fail(403, "ACCOUNT_BLOCKLISTED");
        }
        if (!userMapper.isPasswordResetRequired(user.getId())) {
            return ApiResult.fail(409, "USER_PASSWORD_RESET_NOT_REQUIRED");
        }
        if (safePasswordMatches(request.newPassword(), user.getPasswordHash())) {
            return ApiResult.fail(422, "USER_NEW_PASSWORD_MUST_DIFFER");
        }
        if (userMapper.updatePasswordHash(user.getId(), passwordEncoder.encode(request.newPassword())) != 1
                || userMapper.clearPasswordResetRequired(user.getId()) != 1) {
            throw new IllegalStateException("USER_PASSWORD_RESET_STATE_CHANGED");
        }
        loginGuardMapper.clear(loginKey);
        userMapper.clearLoginFailure(user.getId());
        return issueSession(user, countryCode, phone, clientAddress);
    }

    @Transactional
    public ApiResult<UserLoginResponse> completeTwoFactorLogin(UserTwoFactorLoginRequest request) {
        return completeTwoFactorLogin(request, "local");
    }

    @Transactional
    public ApiResult<UserLoginResponse> completeTwoFactorLogin(
            UserTwoFactorLoginRequest request, String clientAddress) {
        if (request == null || !validCountryCode(request.countryCode()) || !validPhone(request.phone())
                || !StringUtils.hasText(request.password()) || !StringUtils.hasText(request.challengeNo())
                || !StringUtils.hasText(request.code()) || !request.code().trim().matches("\\d{6}")) {
            return ApiResult.fail(422, "USER_TWO_FACTOR_CHALLENGE_INVALID");
        }
        LocalDateTime now = LocalDateTime.now();
        if (!consumeClientRate(clientAddress, now)) return ApiResult.fail(429, "USER_LOGIN_RATE_LIMITED");
        String countryCode = normalizeCountryCode(request.countryCode());
        String phone = request.phone().trim();
        String loginKey = loginKey(countryCode, phone);
        loginGuardMapper.initialize(loginKey, now);
        UserLoginGuardRecord guard = loginGuardMapper.lock(loginKey);
        if (guard != null && guard.getLockedUntil() != null && guard.getLockedUntil().isAfter(now)) {
            return ApiResult.fail(429, "USER_LOGIN_TEMPORARILY_LOCKED");
        }
        UserEntity user = findUser(countryCode, phone);
        if (user == null || !"ACTIVE".equalsIgnoreCase(user.getStatus())
                || !safePasswordMatches(request.password(), user.getPasswordHash())) {
            recordFailure(loginKey, user == null ? null : user.getId(), guard, now);
            return invalidCredential();
        }
        if (blocklistVerifier.isBlocked(user.getId())) {
            return ApiResult.fail(403, "ACCOUNT_BLOCKLISTED");
        }
        if (userMapper.isPasswordResetRequired(user.getId())) {
            return ApiResult.fail(428, "USER_PASSWORD_RESET_REQUIRED");
        }
        if (!userMapper.isTwoFactorEnabled(user.getId())) {
            return ApiResult.fail(409, "USER_TWO_FACTOR_NOT_REQUIRED");
        }
        if (userMapper.consumeValidLoginOtp(user.getId(), request.challengeNo().trim(), request.code().trim()) != 1) {
            userMapper.recordInvalidLoginOtpAttempt(user.getId(), request.challengeNo().trim());
            recordFailure(loginKey, user.getId(), guard, now);
            return ApiResult.fail(422, "USER_TWO_FACTOR_CHALLENGE_INVALID");
        }
        loginGuardMapper.clear(loginKey);
        userMapper.clearLoginFailure(user.getId());
        return issueSession(user, countryCode, phone, clientAddress);
    }

    private ApiResult<UserLoginResponse> issueTwoFactorChallenge(
            UserEntity user, String countryCode, String phone) {
        if (!otpDeliveryService.available()) {
            return ApiResult.fail(503, "USER_OTP_DELIVERY_UNAVAILABLE");
        }
        String challengeNo = "OTP-" + UUID.randomUUID().toString().replace("-", "");
        String code = String.format("%06d", secureRandom.nextInt(1_000_000));
        int ttlMinutes = configInt("auth.risk.otp_ttl_minutes", 5, 1, 15);
        if (userMapper.createLoginOtpChallenge(user.getId(), challengeNo, code, ttlMinutes) != 1) {
            throw new IllegalStateException("USER_TWO_FACTOR_CHALLENGE_CREATE_FAILED");
        }
        try {
            otpDeliveryService.deliver(countryCode, phone, challengeNo, code, ttlMinutes);
        } catch (RuntimeException exception) {
            throw new BizException(503, "USER_OTP_DELIVERY_FAILED");
        }
        String hint = phone.length() <= 4 ? "****" : "****" + phone.substring(phone.length() - 4);
        return new ApiResult<>(428, "USER_TWO_FACTOR_VERIFICATION_REQUIRED",
                UserLoginResponse.challenge(
                        new UserLoginResponse.UserSession(
                                user.getId(), countryCode, hint, user.getNickname()),
                        challengeNo,
                        hint));
    }

    private ApiResult<UserLoginResponse> issueSession(
            UserEntity user, String countryCode, String phone, String clientAddress) {
        String rawRefreshToken = randomRefreshToken();
        String sessionId = hashToken(rawRefreshToken);
        UserSessionEntity session = new UserSessionEntity();
        session.setUserId(user.getId());
        session.setRefreshTokenId(sessionId);
        session.setDeviceName("Nexion App / H5");
        session.setClientIp(StringUtils.hasText(clientAddress) ? clientAddress.trim() : null);
        session.setSessionChainId(UUID.randomUUID().toString());
        session.setLastActiveAt(LocalDateTime.now());
        session.setExpiresAt(LocalDateTime.now().plusDays(configInt("auth.session.refresh_ttl_days", 30, 7, 90)));
        session.setIsDeleted(0);
        sessionMapper.insert(session);

        Duration accessTtl = Duration.ofHours(configInt("auth.session.access_ttl_hours", 4, 1, 24));
        String token = tokenProvider.createToken(user.getId(), "USER", phone, List.of(), sessionId, accessTtl);
        return ApiResult.ok(new UserLoginResponse(token, "Bearer",
                new UserLoginResponse.UserSession(user.getId(), countryCode, phone, user.getNickname()),
                rawRefreshToken));
    }

    @Transactional
    public ApiResult<UserLoginResponse> refresh(UserRefreshRequest request) {
        if (request == null || !StringUtils.hasText(request.refreshToken())
                || request.refreshToken().trim().length() > 512) {
            return ApiResult.fail(401, "USER_REFRESH_TOKEN_INVALID");
        }
        String tokenId = hashToken(request.refreshToken().trim());
        UserSessionEntity current = sessionMapper.findRefreshForUpdate(tokenId);
        if (current == null) return ApiResult.fail(401, "USER_REFRESH_TOKEN_INVALID");
        if (current.getRotationRedeemedAt() != null || current.getRevokedAt() != null) {
            String chainId = StringUtils.hasText(current.getSessionChainId())
                    ? current.getSessionChainId() : tokenId;
            sessionMapper.revokeRefreshChain(chainId);
            outboxService.publish("USER_SECURITY", String.valueOf(current.getUserId()),
                    "auth.refresh_token_reuse_detected", Map.of(
                            "targetUserId", current.getUserId(),
                            "sessionChainId", chainId,
                            "detectedAt", LocalDateTime.now().toString()));
            return ApiResult.fail(401, "USER_REFRESH_TOKEN_REUSE_DETECTED");
        }
        LocalDateTime now = LocalDateTime.now();
        int idleDays = configInt("auth.session.idle_ttl_days", 30, 7, 90);
        LocalDateTime lastActive = current.getLastActiveAt() == null ? current.getCreatedAt() : current.getLastActiveAt();
        if (current.getExpiresAt() == null || !current.getExpiresAt().isAfter(now)
                || lastActive == null || lastActive.plusDays(idleDays).isBefore(now)) {
            sessionMapper.revokeRefreshChain(current.getSessionChainId());
            return ApiResult.fail(401, "USER_REFRESH_TOKEN_EXPIRED");
        }
        UserEntity user = userMapper.selectById(current.getUserId());
        if (user == null || !Integer.valueOf(0).equals(user.getIsDeleted()) || !"ACTIVE".equalsIgnoreCase(user.getStatus())
                || blocklistVerifier.isBlocked(user.getId()) || userMapper.isPasswordResetRequired(user.getId())) {
            sessionMapper.revokeRefreshChain(current.getSessionChainId());
            return ApiResult.fail(403, "USER_REFRESH_NOT_ALLOWED");
        }
        String rawNext = randomRefreshToken();
        String nextId = hashToken(rawNext);
        if (sessionMapper.markRefreshRotated(current.getId(), nextId) != 1) {
            sessionMapper.revokeRefreshChain(current.getSessionChainId());
            return ApiResult.fail(409, "USER_REFRESH_STATE_CHANGED");
        }
        UserSessionEntity next = new UserSessionEntity();
        next.setUserId(user.getId());
        next.setRefreshTokenId(nextId);
        next.setDeviceName(current.getDeviceName());
        next.setClientIp(current.getClientIp());
        next.setSessionChainId(current.getSessionChainId());
        next.setLastActiveAt(now);
        next.setExpiresAt(now.plusDays(configInt("auth.session.refresh_ttl_days", 30, 7, 90)));
        next.setIsDeleted(0);
        sessionMapper.insert(next);
        String phone = user.getPhone() == null ? "" : user.getPhone();
        String countryCode = StringUtils.hasText(user.getCountryCode()) ? normalizeCountryCode(user.getCountryCode()) : "+1";
        String access = tokenProvider.createToken(
                user.getId(), "USER", phone, List.of(), nextId,
                Duration.ofHours(configInt("auth.session.access_ttl_hours", 4, 1, 24)));
        return ApiResult.ok(new UserLoginResponse(access, "Bearer",
                new UserLoginResponse.UserSession(user.getId(), countryCode, phone, user.getNickname()), rawNext));
    }

    @Transactional
    public ApiResult<Map<String, Object>> logout(UserRefreshRequest request) {
        if (request == null || !StringUtils.hasText(request.refreshToken())
                || request.refreshToken().trim().length() > 512) {
            return ApiResult.ok(Map.of("revoked", false));
        }
        String tokenId = hashToken(request.refreshToken().trim());
        UserSessionEntity current = sessionMapper.findRefreshForUpdate(tokenId);
        if (current == null) {
            return ApiResult.ok(Map.of("revoked", false));
        }
        String chainId = StringUtils.hasText(current.getSessionChainId())
                ? current.getSessionChainId() : tokenId;
        sessionMapper.revokeRefreshChain(chainId);
        return ApiResult.ok(Map.of("revoked", true));
    }

    private String randomRefreshToken() {
        byte[] bytes = new byte[32];
        secureRandom.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    private String hashToken(String token) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(token.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 unavailable", exception);
        }
    }

    private UserEntity findUser(String countryCode, String phone) {
        return userMapper.selectOne(new LambdaQueryWrapper<UserEntity>()
                .in(UserEntity::getCountryCode, countryCode, countryCode.substring(1))
                .eq(UserEntity::getPhone, phone)
                .eq(UserEntity::getIsDeleted, 0)
                .last("LIMIT 1"));
    }

    private boolean strongPassword(String password) {
        if (!StringUtils.hasText(password) || password.length() < 12 || password.length() > 72) return false;
        return password.matches(".*[a-z].*") && password.matches(".*[A-Z].*")
                && password.matches(".*\\d.*") && password.matches(".*[^A-Za-z0-9].*");
    }

    private ApiResult<UserLoginResponse> invalidCredential() {
        return ApiResult.fail(401, "USER_CREDENTIAL_INVALID");
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

    private void recordFailure(String loginKey, Long userId, UserLoginGuardRecord guard, LocalDateTime now) {
        int shortWindowMinutes = configInt("auth.risk.lock_duration_minutes", WINDOW_MINUTES, 5, 60);
        int shortThreshold = configInt("auth.risk.login_lock_threshold", MAX_FAILURES, 3, 10);
        int longThreshold = Math.max(
                configInt("auth.risk.login_long_lock_threshold", 10, 5, 20),
                shortThreshold + 1);
        int longLockHours = configInt("auth.risk.long_lock_duration_hours", 24, 12, 48);
        boolean expiredWindow = guard == null || guard.getWindowStartedAt() == null
                || guard.getWindowStartedAt().plusMinutes(shortWindowMinutes).isBefore(now);
        int failedCount = expiredWindow ? 1 : guard.getFailedCount() + 1;
        LocalDateTime windowStartedAt = expiredWindow ? now : guard.getWindowStartedAt();
        LocalDateTime lockedUntil = failedCount >= longThreshold
                ? now.plusHours(longLockHours)
                : failedCount >= shortThreshold ? now.plusMinutes(shortWindowMinutes) : null;
        loginGuardMapper.recordFailure(loginKey, failedCount, windowStartedAt, lockedUntil);
        if (userId != null) {
            userMapper.syncLoginFailure(userId, failedCount);
            boolean newlyLocked = lockedUntil != null && (guard == null || guard.getLockedUntil() == null
                    || !guard.getLockedUntil().isAfter(now));
            if (newlyLocked) {
                String lockType = failedCount >= longThreshold ? "LONG" : "SHORT";
                outboxService.publish("USER_SECURITY", String.valueOf(userId), "auth.login_locked", Map.of(
                        "targetUserId", userId,
                        "loginKeyHash", hashToken(loginKey),
                        "lockType", lockType,
                        "ruleId", "password_or_2fa",
                        "failedCount", failedCount,
                        "lockedUntil", lockedUntil.toString(),
                        "occurredAt", now.toString()));
            }
        }
    }

    private boolean consumeClientRate(String clientAddress, LocalDateTime now) {
        String normalizedAddress = StringUtils.hasText(clientAddress) ? clientAddress.trim() : "unknown";
        String key = loginKey("ip", normalizedAddress);
        loginGuardMapper.initialize(key, now);
        UserLoginGuardRecord guard = loginGuardMapper.lock(key);
        if (guard != null && guard.getLockedUntil() != null && guard.getLockedUntil().isAfter(now)) return false;
        boolean newWindow = guard == null || guard.getWindowStartedAt() == null
                || guard.getWindowStartedAt().plusMinutes(1).isBefore(now);
        int attempts = newWindow ? 1 : guard.getFailedCount() + 1;
        LocalDateTime windowStartedAt = newWindow ? now : guard.getWindowStartedAt();
        LocalDateTime lockedUntil = attempts >= IP_ATTEMPTS_PER_MINUTE ? now.plusMinutes(1) : null;
        loginGuardMapper.recordFailure(key, attempts, windowStartedAt, lockedUntil);
        return true;
    }

    private String loginKey(String countryCode, String phone) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest((countryCode + ':' + phone).getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 unavailable", exception);
        }
    }

    private boolean safePasswordMatches(String rawPassword, String encodedPassword) {
        try {
            return passwordEncoder.matches(rawPassword, encodedPassword);
        } catch (IllegalArgumentException exception) {
            passwordEncoder.matches(rawPassword, DUMMY_PASSWORD_HASH);
            return false;
        }
    }

    private int configInt(String key, int fallback, int min, int max) {
        try {
            int value = configFacade.activeValue(key)
                    .filter(StringUtils::hasText)
                    .map(String::trim)
                    .map(Integer::parseInt)
                    .orElse(fallback);
            return value < min || value > max ? fallback : value;
        } catch (RuntimeException ignored) {
            return fallback;
        }
    }
}
