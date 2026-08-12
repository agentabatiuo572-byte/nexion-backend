package ffdd.opsconsole.auth.application;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import ffdd.opsconsole.auth.dto.UserLoginRequest;
import ffdd.opsconsole.auth.dto.UserLoginResponse;
import ffdd.opsconsole.auth.dto.UserOtpLoginChallengeResponse;
import ffdd.opsconsole.auth.dto.UserOtpLoginRequest;
import ffdd.opsconsole.auth.dto.UserOtpLoginVerifyRequest;
import ffdd.opsconsole.auth.dto.UserPasswordResetCompleteRequest;
import ffdd.opsconsole.auth.dto.UserTwoFactorLoginRequest;
import ffdd.opsconsole.auth.dto.UserRefreshRequest;
import ffdd.opsconsole.auth.infrastructure.UserLoginGuardRecord;
import ffdd.opsconsole.auth.infrastructure.UserOtpSendGuardRecord;
import ffdd.opsconsole.auth.mapper.UserLoginGuardMapper;
import ffdd.opsconsole.shared.api.ApiResult;
import ffdd.opsconsole.platform.facade.PlatformConfigFacade;
import ffdd.opsconsole.shared.exception.BizException;
import ffdd.opsconsole.shared.security.JwtProperties;
import ffdd.opsconsole.shared.security.JwtTokenProvider;
import ffdd.opsconsole.shared.security.UserAuthEnvironment;
import ffdd.opsconsole.shared.security.UserAccountBlocklistVerifier;
import ffdd.opsconsole.shared.security.infrastructure.UserSessionEntity;
import ffdd.opsconsole.shared.security.mapper.AuthSessionMapper;
import ffdd.opsconsole.shared.outbox.EventOutboxService;
import ffdd.opsconsole.user.infrastructure.UserEntity;
import ffdd.opsconsole.user.mapper.UserOpsMapper;
import java.time.LocalDateTime;
import java.time.LocalDate;
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
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

@Service
@RequiredArgsConstructor
public class AppUserAuthService {
    private static final int MAX_FAILURES = 5;
    private static final int WINDOW_MINUTES = 15;
    private static final int IP_ATTEMPTS_PER_MINUTE = 60;
    private static final int OTP_SEND_COOLDOWN_SECONDS = 60;
    private static final int OTP_SEND_WINDOW_MINUTES = 15;
    private static final int OTP_SEND_WINDOW_LIMIT = 5;
    private static final int OTP_SEND_DAY_LIMIT = 10;
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
    private final Environment environment;
    private final SecureRandom secureRandom = new SecureRandom();

    @PostConstruct
    void ensureLoginGuardSchema() {
        loginGuardMapper.createTable();
        loginGuardMapper.createOtpSendGuardTable();
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
        if (UserAuthEnvironment.resolve(environment).isEmpty()) {
            return ApiResult.fail(503, "USER_AUTH_ENVIRONMENT_FORBIDDEN");
        }
        LocalDateTime now = LocalDateTime.now();
        if (!consumeClientRate(clientAddress, now)) {
            return ApiResult.fail(429, "USER_LOGIN_RATE_LIMITED");
        }
        String countryCode = normalizeCountryCode(request.countryCode());
        String phone = request.phone().trim();
        String loginKey = loginKey(countryCode, phone, authNamespace());
        loginGuardMapper.initialize(loginKey, now);
        UserLoginGuardRecord guard = loginGuardMapper.lock(loginKey);
        UserEntity user = findUser(countryCode, phone);
        if (user != null) {
            loginGuardMapper.bindUser(loginKey, user.getId());
        }
        if (guard != null && guard.getLockedUntil() != null && guard.getLockedUntil().isAfter(now)) {
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
        ApiResult<UserLoginResponse> environmentFailure = environmentFailure(user, false);
        if (environmentFailure != null) return environmentFailure;

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

    /**
     * Starts the passwordless login path without repurposing registration or
     * second-factor challenges. Ineligible identities receive an indistinguishable
     * disposable challenge so this public endpoint cannot be used for account
     * enumeration; only a persisted LOGIN challenge can complete authentication.
     */
    @Transactional
    public ApiResult<UserOtpLoginChallengeResponse> beginOtpLogin(UserOtpLoginRequest request) {
        return beginOtpLogin(request, "local");
    }

    @Transactional
    public ApiResult<UserOtpLoginChallengeResponse> beginOtpLogin(
            UserOtpLoginRequest request, String clientAddress) {
        if (request == null || !validCountryCode(request.countryCode()) || !validPhone(request.phone())) {
            return ApiResult.fail(422, "USER_OTP_LOGIN_REQUEST_INVALID");
        }
        LocalDateTime now = LocalDateTime.now();
        if (!consumeClientRate(clientAddress, now)) return ApiResult.fail(429, "USER_LOGIN_RATE_LIMITED");
        String countryCode = normalizeCountryCode(request.countryCode());
        String phone = request.phone().trim();
        String loginKey = loginKey(countryCode, phone, authNamespace());
        loginGuardMapper.initialize(loginKey, now);
        UserLoginGuardRecord guard = loginGuardMapper.lock(loginKey);
        UserEntity user = findUser(countryCode, phone);
        if (user != null) loginGuardMapper.bindUser(loginKey, user.getId());
        if (guard != null && guard.getLockedUntil() != null && guard.getLockedUntil().isAfter(now)) {
            return ApiResult.fail(429, "USER_LOGIN_TEMPORARILY_LOCKED");
        }
        if (!consumeOtpSendRate(loginKey, now)) {
            return ApiResult.fail(429, "USER_OTP_SEND_RATE_LIMITED");
        }
        boolean eligible = user != null
                && "ACTIVE".equalsIgnoreCase(user.getStatus())
                && !blocklistVerifier.isBlocked(user.getId())
                && !userMapper.isPasswordResetRequired(user.getId())
                && !userMapper.isTwoFactorEnabled(user.getId())
                && UserAuthEnvironmentPolicy.evaluate(environment, user) == UserAuthEnvironmentPolicy.Decision.ALLOW
                && otpDeliveryService.available();
        if (!eligible) return ApiResult.ok(disposableOtpLoginChallenge(phone));
        String challengeNo = "LOGIN-" + UUID.randomUUID().toString().replace("-", "");
        String code = String.format("%06d", secureRandom.nextInt(1_000_000));
        int ttlMinutes = configInt("auth.risk.otp_ttl_minutes", 5, 1, 15);
        userMapper.invalidateOpenLoginOtpChallenges(user.getId());
        if (userMapper.createLoginOtpChallenge(user.getId(), challengeNo, code, ttlMinutes) != 1) {
            throw new IllegalStateException("USER_OTP_LOGIN_CHALLENGE_CREATE_FAILED");
        }
        try {
            otpDeliveryService.deliver(countryCode, phone, challengeNo, code, ttlMinutes);
        } catch (RuntimeException exception) {
            // Do not expose delivery availability as an account-existence oracle.
            return ApiResult.ok(disposableOtpLoginChallenge(phone));
        }
        return ApiResult.ok(new UserOtpLoginChallengeResponse(challengeNo, 60, maskPhone(phone)));
    }

    @Transactional
    public ApiResult<UserLoginResponse> completeOtpLogin(UserOtpLoginVerifyRequest request) {
        return completeOtpLogin(request, "local");
    }

    @Transactional
    public ApiResult<UserLoginResponse> completeOtpLogin(
            UserOtpLoginVerifyRequest request, String clientAddress) {
        if (request == null || !validCountryCode(request.countryCode()) || !validPhone(request.phone())
                || !StringUtils.hasText(request.challengeNo()) || !request.challengeNo().trim().matches("LOGIN-[a-f0-9]{32}")
                || !StringUtils.hasText(request.code()) || !request.code().trim().matches("\\d{6}")) {
            return ApiResult.fail(422, "USER_OTP_LOGIN_CHALLENGE_INVALID");
        }
        LocalDateTime now = LocalDateTime.now();
        if (!consumeClientRate(clientAddress, now)) return ApiResult.fail(429, "USER_LOGIN_RATE_LIMITED");
        String countryCode = normalizeCountryCode(request.countryCode());
        String phone = request.phone().trim();
        String loginKey = loginKey(countryCode, phone, authNamespace());
        loginGuardMapper.initialize(loginKey, now);
        UserLoginGuardRecord guard = loginGuardMapper.lock(loginKey);
        UserEntity user = findUser(countryCode, phone);
        if (user != null) loginGuardMapper.bindUser(loginKey, user.getId());
        boolean eligible = user != null
                && "ACTIVE".equalsIgnoreCase(user.getStatus())
                && !blocklistVerifier.isBlocked(user.getId())
                && !userMapper.isPasswordResetRequired(user.getId())
                && !userMapper.isTwoFactorEnabled(user.getId())
                && UserAuthEnvironmentPolicy.evaluate(environment, user) == UserAuthEnvironmentPolicy.Decision.ALLOW
                && (guard == null || guard.getLockedUntil() == null || !guard.getLockedUntil().isAfter(now));
        ApiResult<UserLoginResponse> environmentFailure = user == null ? null : environmentFailure(user, false);
        if (environmentFailure != null) return environmentFailure;
        int consumed = eligible
                ? userMapper.consumeValidLoginOtp(user.getId(), request.challengeNo().trim(), request.code().trim())
                : 0;
        if (consumed != 1) {
            if (user != null) {
                userMapper.recordInvalidLoginOtpAttempt(user.getId(), request.challengeNo().trim());
            }
            recordFailure(loginKey, user == null ? null : user.getId(), guard, now);
            return ApiResult.fail(422, "USER_OTP_LOGIN_CHALLENGE_INVALID");
        }
        loginGuardMapper.clear(loginKey);
        userMapper.clearLoginFailure(user.getId());
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
        String loginKey = loginKey(countryCode, phone, authNamespace());
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
        ApiResult<UserLoginResponse> environmentFailure = environmentFailure(user, false);
        if (environmentFailure != null) return environmentFailure;
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
                || !request.challengeNo().trim().matches("OTP-[a-f0-9]{32}")
                || !StringUtils.hasText(request.code()) || !request.code().trim().matches("\\d{6}")) {
            return ApiResult.fail(422, "USER_TWO_FACTOR_CHALLENGE_INVALID");
        }
        LocalDateTime now = LocalDateTime.now();
        if (!consumeClientRate(clientAddress, now)) return ApiResult.fail(429, "USER_LOGIN_RATE_LIMITED");
        String countryCode = normalizeCountryCode(request.countryCode());
        String phone = request.phone().trim();
        String loginKey = loginKey(countryCode, phone, authNamespace());
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
        ApiResult<UserLoginResponse> environmentFailure = environmentFailure(user, false);
        if (environmentFailure != null) return environmentFailure;
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
        ApiResult<UserLoginResponse> environmentFailure = environmentFailure(user, false);
        if (environmentFailure != null) return environmentFailure;
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
        UserAuthEnvironment audience = UserAuthEnvironment.resolve(environment)
                .orElseThrow(() -> new BizException(503, "USER_AUTH_ENVIRONMENT_FORBIDDEN"));
        String token = tokenProvider.createUserToken(user.getId(), phone, List.of(), sessionId, accessTtl, audience);
        return ApiResult.ok(new UserLoginResponse(token, "Bearer",
                new UserLoginResponse.UserSession(user.getId(), countryCode, phone, user.getNickname()),
                rawRefreshToken));
    }

    /**
     * Registration owns identity creation and sponsor attribution; session
     * issuance remains centralized here so login and registration share the
     * same token, refresh and device-session contract.
     */
    ApiResult<UserLoginResponse> issueRegisteredSession(
            UserEntity user,
            String clientAddress) {
        if (user == null || user.getId() == null || !StringUtils.hasText(user.getPhone())) {
            throw new BizException(422, "USER_REGISTRATION_SESSION_INVALID");
        }
        String countryCode = StringUtils.hasText(user.getCountryCode())
                ? normalizeCountryCode(user.getCountryCode())
                : "+1";
        return issueSession(user, countryCode, user.getPhone(), clientAddress);
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
        ApiResult<UserLoginResponse> environmentFailure = environmentFailure(user, true);
        if (environmentFailure != null) {
            sessionMapper.revokeRefreshChain(current.getSessionChainId());
            return environmentFailure;
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
        UserAuthEnvironment audience = UserAuthEnvironment.resolve(environment)
                .orElseThrow(() -> new BizException(503, "USER_AUTH_ENVIRONMENT_FORBIDDEN"));
        String access = tokenProvider.createUserToken(
                user.getId(), phone, List.of(), nextId,
                Duration.ofHours(configInt("auth.session.access_ttl_hours", 4, 1, 24)), audience);
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
        UserAuthEnvironment audience = UserAuthEnvironment.resolve(environment).orElse(null);
        if (audience == null) return null;
        return userMapper.selectOne(new LambdaQueryWrapper<UserEntity>()
                .in(UserEntity::getCountryCode, countryCode, countryCode.substring(1))
                .eq(UserEntity::getPhone, phone)
                .eq(UserEntity::getSandbox, audience == UserAuthEnvironment.SANDBOX ? 1 : 0)
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

    private ApiResult<UserLoginResponse> environmentFailure(UserEntity user, boolean refresh) {
        return switch (UserAuthEnvironmentPolicy.evaluate(environment, user)) {
            case ALLOW -> null;
            case ACCOUNT_MISMATCH -> ApiResult.fail(403,
                    refresh ? "USER_REFRESH_ENVIRONMENT_FORBIDDEN" : "USER_AUTH_ENVIRONMENT_FORBIDDEN");
            case PROFILE_FORBIDDEN -> ApiResult.fail(503, "USER_AUTH_ENVIRONMENT_FORBIDDEN");
        };
    }

    private UserOtpLoginChallengeResponse disposableOtpLoginChallenge(String phone) {
        return new UserOtpLoginChallengeResponse(
                "LOGIN-" + UUID.randomUUID().toString().replace("-", ""),
                60,
                maskPhone(phone));
    }

    private String maskPhone(String phone) {
        return phone.length() <= 4 ? "****" : "****" + phone.substring(phone.length() - 4);
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

    private boolean consumeOtpSendRate(String loginKey, LocalDateTime now) {
        int cooldownSeconds = configInt("auth.risk.otp_send_cooldown_seconds", OTP_SEND_COOLDOWN_SECONDS, 30, 300);
        int windowMinutes = configInt("auth.risk.otp_send_window_minutes", OTP_SEND_WINDOW_MINUTES, 5, 60);
        int windowLimit = configInt("auth.risk.otp_send_window_limit", OTP_SEND_WINDOW_LIMIT, 2, 20);
        int dayLimit = configInt("auth.risk.otp_send_day_limit", OTP_SEND_DAY_LIMIT, 5, 50);
        loginGuardMapper.initializeOtpSendGuard(loginKey, now);
        UserOtpSendGuardRecord guard = loginGuardMapper.lockOtpSendGuard(loginKey);
        if (guard == null) throw new IllegalStateException("USER_OTP_SEND_GUARD_UNAVAILABLE");
        if (guard.getLastSentAt() != null && guard.getLastSentAt().plusSeconds(cooldownSeconds).isAfter(now)) {
            return false;
        }
        boolean newWindow = guard.getWindowStartedAt() == null
                || !guard.getWindowStartedAt().plusMinutes(windowMinutes).isAfter(now);
        boolean newDay = guard.getDayStartedAt() == null || !guard.getDayStartedAt().equals(now.toLocalDate());
        int windowCount = newWindow ? 1 : guard.getWindowSendCount() + 1;
        int dayCount = newDay ? 1 : guard.getDaySendCount() + 1;
        if (windowCount > windowLimit || dayCount > dayLimit) return false;
        LocalDateTime windowStartedAt = newWindow ? now : guard.getWindowStartedAt();
        LocalDate dayStartedAt = newDay ? now.toLocalDate() : guard.getDayStartedAt();
        if (loginGuardMapper.recordOtpSend(
                loginKey, now, windowStartedAt, windowCount, dayStartedAt, dayCount) != 1) {
            throw new IllegalStateException("USER_OTP_SEND_GUARD_STATE_CHANGED");
        }
        return true;
    }

    private String authNamespace() {
        return UserAuthEnvironment.resolve(environment).map(Enum::name).orElse("FORBIDDEN");
    }

    private String loginKey(String countryCode, String phone) {
        return loginKey(countryCode, phone, authNamespace());
    }

    private String loginKey(String countryCode, String phone, String namespace) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest((namespace + ':' + countryCode + ':' + phone).getBytes(StandardCharsets.UTF_8));
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
