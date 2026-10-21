package ffdd.opsconsole.auth.application;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import ffdd.opsconsole.auth.dto.UserLoginRequest;
import ffdd.opsconsole.auth.captcha.CaptchaOtpGate;
import ffdd.opsconsole.auth.captcha.CaptchaScene;
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
import ffdd.opsconsole.shared.config.DateTimeFormatConfig;
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
import org.springframework.security.core.Authentication;
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
    private final CaptchaOtpGate captchaGate;
    private final UserOtpDeliveryService otpDeliveryService;
    private final EventOutboxService outboxService;
    private final Environment environment;
    private final ffdd.opsconsole.content.application.SupportActivityService supportActivity;
    private final SecureRandom secureRandom = new SecureRandom();
    private final AppSingleSessionService singleSessions;
    private final ffdd.opsconsole.shared.security.UserSessionRevocationService revocations;

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
        return login(request, clientAddress, AppSessionSurface.UNKNOWN);
    }

    @Transactional
    public ApiResult<UserLoginResponse> login(
            UserLoginRequest request, String clientAddress, AppSessionSurface surface) {
        return login(request, clientAddress, AppLoginContext.unavailable(surface));
    }

    @Transactional
    public ApiResult<UserLoginResponse> login(
            UserLoginRequest request, String clientAddress, AppLoginContext context) {
        ApiResult<UserLoginResponse> recovered = recoverCompletedLogin(context);
        if (recovered != null) return recovered;
        if (request == null || !validCountryCode(request.countryCode())
                || !validPhone(request.countryCode(), request.phone())) {
            return ApiResult.fail(422, "USER_LOGIN_PHONE_INVALID");
        }
        if (!StringUtils.hasText(request.password())) {
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
        UserEntity user = lockLoginUser(findUser(countryCode, phone));
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
            return issueTwoFactorChallenge(user, countryCode, phone, request.captchaTicket(), clientAddress);
        }

        return issueSession(user, countryCode, phone, clientAddress, context, true);
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
        if (request == null || !validCountryCode(request.countryCode()) || !validPhone(request.countryCode(), request.phone())) {
            return ApiResult.fail(422, "USER_OTP_LOGIN_REQUEST_INVALID");
        }
        if (UserAuthEnvironment.resolve(environment).isEmpty()) {
            return ApiResult.fail(503, "USER_AUTH_ENVIRONMENT_FORBIDDEN");
        }
        LocalDateTime now = LocalDateTime.now();
        if (!consumeClientRate(clientAddress, now)) return ApiResult.fail(429, "USER_LOGIN_RATE_LIMITED");
        String countryCode = normalizeCountryCode(request.countryCode());
        String phone = request.phone().trim();
        String loginKey = loginKey(countryCode, phone, authNamespace());
        String otpSendKey = otpSendKey(countryCode, phone, authNamespace());
        loginGuardMapper.initialize(loginKey, now);
        UserLoginGuardRecord guard = loginGuardMapper.lock(loginKey);
        UserEntity user = lockLoginUser(findUser(countryCode, phone));
        if (user != null) loginGuardMapper.bindUser(loginKey, user.getId());
        if (guard != null && guard.getLockedUntil() != null && guard.getLockedUntil().isAfter(now)) {
            return ApiResult.fail(429, "USER_LOGIN_TEMPORARILY_LOCKED");
        }
        AppOtpPolicy policy = AppOtpPolicy.load(configFacade);
        OtpSendRateDecision otpRate = consumeOtpSendRate(otpSendKey, now, policy,
                CaptchaScene.LOGIN, request.captchaTicket(), clientAddress);
        if (otpRate != OtpSendRateDecision.ALLOWED) {
            return captchaOrRateFailure(otpRate);
        }
        boolean eligible = user != null
                && "ACTIVE".equalsIgnoreCase(user.getStatus())
                && !blocklistVerifier.isBlocked(user.getId())
                && !userMapper.isPasswordResetRequired(user.getId())
                && !userMapper.isTwoFactorEnabled(user.getId())
                && UserAuthEnvironmentPolicy.evaluate(environment, user) == UserAuthEnvironmentPolicy.Decision.ALLOW
                && otpDeliveryService.available(countryCode);
        if (!eligible) return ApiResult.ok(disposableOtpLoginChallenge(phone, policy));
        String challengeNo = "LOGIN-" + UUID.randomUUID().toString().replace("-", "");
        String code = otpDeliveryService.verificationCode(countryCode);
        int ttlMinutes = policy.ttlMinutes();
        userMapper.invalidateOpenLoginOtpChallenges(user.getId());
        if (userMapper.createLoginOtpChallenge(user.getId(), challengeNo, code, ttlMinutes) != 1) {
            throw new IllegalStateException("USER_OTP_LOGIN_CHALLENGE_CREATE_FAILED");
        }
        try {
            otpDeliveryService.deliver(countryCode, phone, challengeNo, code, ttlMinutes);
        } catch (RuntimeException exception) {
            userMapper.invalidateOpenLoginOtpChallenges(user.getId());
            return ApiResult.fail(503, "USER_OTP_DELIVERY_FAILED");
        }
        return ApiResult.ok(new UserOtpLoginChallengeResponse(challengeNo, policy.cooldownSeconds(), maskPhone(phone)));
    }

    @Transactional
    public ApiResult<UserLoginResponse> completeOtpLogin(UserOtpLoginVerifyRequest request) {
        return completeOtpLogin(request, "local");
    }

    @Transactional
    public ApiResult<UserLoginResponse> completeOtpLogin(
            UserOtpLoginVerifyRequest request, String clientAddress) {
        return completeOtpLogin(request, clientAddress, AppSessionSurface.UNKNOWN);
    }

    @Transactional
    public ApiResult<UserLoginResponse> completeOtpLogin(
            UserOtpLoginVerifyRequest request, String clientAddress, AppSessionSurface surface) {
        return completeOtpLogin(request, clientAddress, AppLoginContext.unavailable(surface));
    }

    @Transactional
    public ApiResult<UserLoginResponse> completeOtpLogin(
            UserOtpLoginVerifyRequest request, String clientAddress, AppLoginContext context) {
        ApiResult<UserLoginResponse> recovered = recoverCompletedLogin(context);
        if (recovered != null) return recovered;
        if (request == null || !validCountryCode(request.countryCode()) || !validPhone(request.countryCode(), request.phone())
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
        UserEntity user = lockLoginUser(findUser(countryCode, phone));
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
        if (eligible) {
            sessionMapper.lockAccount(user.getId());
            var committed = singleSessions.recoverCompletedForUser(user.getId(), context);
            if (committed != null) return loginReceipt(user, committed);
        }
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
        return issueSession(user, countryCode, phone, clientAddress, context, true);
    }

    @Transactional
    public ApiResult<UserLoginResponse> completePasswordReset(UserPasswordResetCompleteRequest request) {
        return completePasswordReset(request, "local");
    }

    @Transactional
    public ApiResult<UserLoginResponse> completePasswordReset(
            UserPasswordResetCompleteRequest request, String clientAddress) {
        return completePasswordReset(request, clientAddress, AppLoginContext.unavailable(null));
    }

    @Transactional
    public ApiResult<UserLoginResponse> completePasswordReset(
            UserPasswordResetCompleteRequest request, String clientAddress, AppLoginContext context) {
        ApiResult<UserLoginResponse> recovered = recoverCompletedLogin(context);
        if (recovered != null) return recovered;
        if (request == null || !validCountryCode(request.countryCode()) || !validPhone(request.countryCode(), request.phone())
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
        UserEntity user = lockLoginUser(findUser(countryCode, phone));
        if (user != null) {
            var completed = singleSessions.recoverCompletedForUser(user.getId(), context);
            if (completed != null) return loginReceipt(user, completed);
        }
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
        revocations.revokeAll(user.getId());
        return issueSession(user, countryCode, phone, clientAddress, context, true);
    }

    @Transactional
    public ApiResult<UserLoginResponse> completeTwoFactorLogin(UserTwoFactorLoginRequest request) {
        return completeTwoFactorLogin(request, "local");
    }

    @Transactional
    public ApiResult<UserLoginResponse> completeTwoFactorLogin(
            UserTwoFactorLoginRequest request, String clientAddress) {
        return completeTwoFactorLogin(request, clientAddress, AppSessionSurface.UNKNOWN);
    }

    @Transactional
    public ApiResult<UserLoginResponse> completeTwoFactorLogin(
            UserTwoFactorLoginRequest request, String clientAddress, AppSessionSurface surface) {
        return completeTwoFactorLogin(request, clientAddress, AppLoginContext.unavailable(surface));
    }

    @Transactional
    public ApiResult<UserLoginResponse> completeTwoFactorLogin(
            UserTwoFactorLoginRequest request, String clientAddress, AppLoginContext context) {
        ApiResult<UserLoginResponse> recovered = recoverCompletedLogin(context);
        if (recovered != null) return recovered;
        if (request == null || !validCountryCode(request.countryCode()) || !validPhone(request.countryCode(), request.phone())
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
        UserEntity user = lockLoginUser(findUser(countryCode, phone));
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
        var committed = singleSessions.recoverCompletedForUser(user.getId(), context);
        if (committed != null) return loginReceipt(user, committed);
        if (userMapper.consumeValidLoginOtp(user.getId(), request.challengeNo().trim(), request.code().trim()) != 1) {
            userMapper.recordInvalidLoginOtpAttempt(user.getId(), request.challengeNo().trim());
            recordFailure(loginKey, user.getId(), guard, now);
            return ApiResult.fail(422, "USER_TWO_FACTOR_CHALLENGE_INVALID");
        }
        loginGuardMapper.clear(loginKey);
        userMapper.clearLoginFailure(user.getId());
        return issueSession(user, countryCode, phone, clientAddress, context, true);
    }

    private ApiResult<UserLoginResponse> issueTwoFactorChallenge(
            UserEntity user, String countryCode, String phone, String captchaTicket, String clientAddress) {
        if (!otpDeliveryService.available(countryCode)) {
            return ApiResult.fail(503, "USER_OTP_DELIVERY_UNAVAILABLE");
        }
        AppOtpPolicy policy = AppOtpPolicy.load(configFacade);
        OtpSendRateDecision otpRate = consumeOtpSendRate(
                otpSendKey(countryCode, phone, authNamespace()), LocalDateTime.now(), policy,
                CaptchaScene.LOGIN, captchaTicket, clientAddress);
        if (otpRate != OtpSendRateDecision.ALLOWED) {
            return captchaOrRateFailure(otpRate);
        }
        String challengeNo = "OTP-" + UUID.randomUUID().toString().replace("-", "");
        String code = otpDeliveryService.verificationCode(countryCode);
        int ttlMinutes = policy.ttlMinutes();
        userMapper.invalidateOpenLoginOtpChallenges(user.getId());
        if (userMapper.createLoginOtpChallenge(user.getId(), challengeNo, code, ttlMinutes) != 1) {
            throw new IllegalStateException("USER_TWO_FACTOR_CHALLENGE_CREATE_FAILED");
        }
        try {
            otpDeliveryService.deliver(countryCode, phone, challengeNo, code, ttlMinutes);
        } catch (RuntimeException exception) {
            userMapper.invalidateOpenLoginOtpChallenges(user.getId());
            return ApiResult.fail(503, "USER_OTP_DELIVERY_FAILED");
        }
        String hint = phone.length() <= 4 ? "****" : "****" + phone.substring(phone.length() - 4);
        return new ApiResult<>(428, "USER_TWO_FACTOR_VERIFICATION_REQUIRED",
                UserLoginResponse.challenge(
                        new UserLoginResponse.UserSession(
                                user.getId(), countryCode, hint, user.getNickname(),
                                userMapper.isOnboardingComplete(user.getId())),
                        challengeNo,
                        hint));
    }

    private ApiResult<UserLoginResponse> issueSession(
            UserEntity user, String countryCode, String phone, String clientAddress) {
        return issueSession(user, countryCode, phone, clientAddress, AppLoginContext.unavailable(null), true);
    }

    private ApiResult<UserLoginResponse> issueSession(
            UserEntity user, String countryCode, String phone, String clientAddress, AppLoginContext context,
            boolean interactiveLogin) {
        ApiResult<UserLoginResponse> environmentFailure = environmentFailure(user, false);
        if (environmentFailure != null) return environmentFailure;
        var issued = singleSessions.issue(user.getId(), context, clientAddress,
                configInt("auth.session.refresh_ttl_days", 30, 7, 90));
        UserSessionEntity session = issued.session();
        UserAuthEnvironment audience = UserAuthEnvironment.resolve(environment)
                .orElseThrow(() -> new BizException(503, "USER_AUTH_ENVIRONMENT_FORBIDDEN"));
        if (!issued.original() && interactiveLogin && audience == UserAuthEnvironment.PRODUCTION)
            supportActivity.interactiveLogin(user.getId(), session.getSessionChainId());
        return loginReceipt(user, issued);
    }

    private ApiResult<UserLoginResponse> loginReceipt(UserEntity user, AppSingleSessionService.Issued issued) {
        UserSessionEntity session = issued.session();
        String token = issued.refreshToken() == null ? null : tokenProvider.createUserToken(user.getId(), user.getPhone(),
                List.of(), session.getRefreshTokenId(), Duration.ofHours(configInt("auth.session.access_ttl_hours",4,1,24)),
                UserAuthEnvironment.resolve(environment).orElseThrow(() -> new BizException(503,"USER_AUTH_ENVIRONMENT_FORBIDDEN")));
        return ApiResult.ok(new UserLoginResponse(token, "Bearer",
                new UserLoginResponse.UserSession(user.getId(), normalizeCountryCode(user.getCountryCode()), user.getPhone(),
                        user.getNickname(), userMapper.isOnboardingComplete(user.getId())), null, null,
                issued.refreshToken(), null, tokenProvider.sessionSyncKey(session.getSessionChainId()))
                .withContext(session, issued.status()));
    }

    @Transactional
    public ApiResult<UserLoginResponse> recoverCompletedLogin(AppLoginContext context) {
        context.validate();
        return singleSessions.hasAttempt(context.loginAttemptId()) ? recoverLogin(context) : null;
    }

    @Transactional
    public ApiResult<UserLoginResponse> recoverLogin(AppLoginContext context) {
        if (context != null && "OAUTH".equals(context.authMethod())
                && context.authReference() != null
                && java.util.Set.of("GOOGLE", "APPLE", "TELEGRAM").contains(
                        context.authReference().split(":", 2)[0].trim().toUpperCase(java.util.Locale.ROOT))
                && !OAuthProviderClient.enabled(environment)) {
            throw new BizException(503, "OAUTH_DISABLED");
        }
        var issued = singleSessions.recover(context);
        UserEntity user = userMapper.selectById(issued.session().getUserId());
        if (user == null || !"ACTIVE".equals(user.getStatus()) || blocklistVerifier.isBlocked(user.getId())
                || userMapper.isPasswordResetRequired(user.getId())) throw new BizException(403,"USER_REFRESH_NOT_ALLOWED");
        ApiResult<UserLoginResponse> failure = environmentFailure(user, false);
        return failure == null ? loginReceipt(user, issued) : failure;
    }

    /**
     * Registration owns identity creation and sponsor attribution; session
     * issuance remains centralized here so login and registration share the
     * same token, refresh and device-session contract.
     */
    ApiResult<UserLoginResponse> issueRegisteredSession(
            UserEntity user,
            String clientAddress) {
        return issueRegisteredSession(user, clientAddress, AppSessionSurface.UNKNOWN);
    }

    ApiResult<UserLoginResponse> issueRegisteredSession(
            UserEntity user, String clientAddress, AppSessionSurface surface) {
        return issueRegisteredSession(user, clientAddress, AppLoginContext.unavailable(surface));
    }

    ApiResult<UserLoginResponse> issueRegisteredSession(
            UserEntity user, String clientAddress, AppLoginContext context) {
        if (user == null || user.getId() == null || !StringUtils.hasText(user.getPhone())) {
            throw new BizException(422, "USER_REGISTRATION_SESSION_INVALID");
        }
        if (!validCountryCode(user.getCountryCode())) {
            throw new BizException(422, "USER_REGISTRATION_COUNTRY_CODE_FORBIDDEN");
        }
        if (!validPhone(user.getCountryCode(), user.getPhone())) {
            throw new BizException(422, "USER_REGISTRATION_PHONE_INVALID");
        }
        String countryCode = normalizeCountryCode(user.getCountryCode());
        return issueSession(user, countryCode, user.getPhone(), clientAddress, context, false);
    }

    /** Called only after the provider flow verifies the browser proof and resolves its linked account. */
    ApiResult<UserLoginResponse> issueProviderSession(Long userId, String clientAddress, AppLoginContext context,
            String challengeNo, String code, String captchaTicket) {
        if (!OAuthProviderClient.enabled(environment)) return ApiResult.fail(503, "OAUTH_DISABLED");
        ApiResult<UserLoginResponse> recovered = recoverCompletedLogin(context);
        if (recovered != null) return recovered;
        LocalDateTime now = LocalDateTime.now();
        if (!consumeClientRate(clientAddress, now)) return ApiResult.fail(429, "USER_LOGIN_RATE_LIMITED");
        UserEntity snapshot = userMapper.selectById(userId);
        if (snapshot == null || !validCountryCode(snapshot.getCountryCode())
                || !validPhone(snapshot.getCountryCode(), snapshot.getPhone()))
            return ApiResult.fail(403, "OAUTH_IDENTITY_NOT_AVAILABLE");
        String country = normalizeCountryCode(snapshot.getCountryCode());
        String key = loginKey(country, snapshot.getPhone(), authNamespace());
        // Match password/OTP login's guard -> account lock order.
        loginGuardMapper.initialize(key, now);
        UserLoginGuardRecord guard = loginGuardMapper.lock(key);
        if (guard != null && guard.getLockedUntil() != null && guard.getLockedUntil().isAfter(now))
            return ApiResult.fail(429, "USER_LOGIN_TEMPORARILY_LOCKED");
        UserEntity user = userMapper.findLoginUserForUpdate(userId);
        if (user == null || !"ACTIVE".equalsIgnoreCase(user.getStatus())
                || !Integer.valueOf(0).equals(user.getIsDeleted()) || blocklistVerifier.isBlocked(userId))
            return ApiResult.fail(403, "OAUTH_IDENTITY_NOT_AVAILABLE");
        ApiResult<UserLoginResponse> environmentFailure = environmentFailure(user, false);
        if (environmentFailure != null) return environmentFailure;
        if (!validCountryCode(user.getCountryCode()) || !validPhone(user.getCountryCode(), user.getPhone()))
            return ApiResult.fail(403, "OAUTH_IDENTITY_NOT_AVAILABLE");
        if (userMapper.isPasswordResetRequired(userId)) return ApiResult.fail(428, "USER_PASSWORD_RESET_REQUIRED");
        if (!country.equals(normalizeCountryCode(user.getCountryCode())) || !snapshot.getPhone().equals(user.getPhone()))
            return ApiResult.fail(409, "OAUTH_IDENTITY_NOT_AVAILABLE");
        loginGuardMapper.bindUser(key, userId);
        if (userMapper.isTwoFactorEnabled(userId)) {
            if (!StringUtils.hasText(challengeNo) && !StringUtils.hasText(code))
                return issueTwoFactorChallenge(user, country, user.getPhone(), captchaTicket, clientAddress);
            if (challengeNo == null || !challengeNo.matches("OTP-[a-f0-9]{32}")
                    || code == null || !code.matches("\\d{6}"))
                return ApiResult.fail(422, "USER_TWO_FACTOR_CHALLENGE_INVALID");
            if (userMapper.consumeValidLoginOtp(userId, challengeNo, code) != 1) {
                userMapper.recordInvalidLoginOtpAttempt(userId, challengeNo);
                recordFailure(key, userId, guard, now);
                return ApiResult.fail(422, "USER_TWO_FACTOR_CHALLENGE_INVALID");
            }
        }
        loginGuardMapper.clear(key);
        userMapper.clearLoginFailure(userId);
        return issueSession(user, country, user.getPhone(), clientAddress, context, true);
    }

    /** Linking requires a current authenticated session plus its owner's password. */
    ApiResult<Void> verifyProviderLink(Long userId, String password, String clientAddress) {
        LocalDateTime now = LocalDateTime.now();
        if (!consumeClientRate(clientAddress, now)) return ApiResult.fail(429, "USER_LOGIN_RATE_LIMITED");
        UserEntity user = userMapper.findLoginUserForUpdate(userId);
        if (user == null || !"ACTIVE".equalsIgnoreCase(user.getStatus())
                || !Integer.valueOf(0).equals(user.getIsDeleted()) || blocklistVerifier.isBlocked(userId)
                || environmentFailure(user, false) != null)
            return ApiResult.fail(403, "OAUTH_IDENTITY_NOT_AVAILABLE");
        if (password == null || password.length() > 128 || !safePasswordMatches(password, user.getPasswordHash()))
            return ApiResult.fail(403, "OAUTH_LINK_PASSWORD_INVALID");
        if (userMapper.isPasswordResetRequired(userId))
            return ApiResult.fail(428, "USER_PASSWORD_RESET_REQUIRED");
        return ApiResult.ok();
    }

    private ApiResult<UserLoginResponse> endedRefresh(UserSessionEntity session, String message, String fallback) {
        // A refresh secret is resolved by its hash, then account/session locks are held by refresh().
        // Do not reveal even a reason across the server-owned environment boundary.
        UserEntity user = userMapper.findLoginUserForUpdate(session.getUserId());
        var audience = UserAuthEnvironment.resolve(environment).orElse(null);
        if (user == null || audience == null || !audience.acceptsSandbox(user.getSandbox()))
            return ApiResult.fail(401, message);
        String reason = session.getSessionEndedReason();
        if (!"APP".equals(session.getSessionGroup()) && !"H5".equals(session.getSessionGroup())) reason = "REAUTH_REQUIRED";
        else if (reason == null || !List.of("APP_REPLACED", "CURRENT_LOGOUT", "SECURITY_REVOKED", "SESSION_EXPIRED", "REAUTH_REQUIRED").contains(reason)
                || ("APP_REPLACED".equals(reason) && !"APP".equals(session.getSessionGroup()))) reason = fallback;
        return ApiResult.fail(401, message, UserLoginResponse.ended(reason));
    }

    @Transactional
    public ApiResult<UserLoginResponse> refresh(UserRefreshRequest request) {
        return refresh(request, null, false);
    }

    @Transactional
    public ApiResult<UserLoginResponse> refresh(UserRefreshRequest request, String rotationNonce, boolean cookieMode) {
        if (request == null || !StringUtils.hasText(request.refreshToken())
                || request.refreshToken().trim().length() > 512) {
            return ApiResult.fail(401, "USER_REFRESH_TOKEN_INVALID");
        }
        String rawCurrent = request.refreshToken().trim();
        String tokenId = hashToken(rawCurrent);
        UserSessionEntity routed = sessionMapper.findSession(tokenId);
        if (routed != null && sessionMapper.lockAccount(routed.getUserId()) == null)
            throw new BizException(403, "USER_AUTH_REQUIRED");
        UserSessionEntity current = routed == null ? null : sessionMapper.findRefreshForUpdate(tokenId);
        if (current == null) return ApiResult.fail(401, "USER_REFRESH_TOKEN_INVALID");
        if (sessionMapper.countFixedContext(tokenId, current.getUserId()) != 1)
            return endedRefresh(current, "SESSION_CONTEXT_REAUTH_REQUIRED", "REAUTH_REQUIRED");
        String recoveredRaw = null;
        // Both credential transports can recover only the same authenticated rotation intent.
        boolean nonceRotation = validRotationNonce(rotationNonce);
        if (current.getRotationRedeemedAt() != null || current.getRevokedAt() != null) {
            if (nonceRotation && current.getRotationRedeemedAt() != null
                    && StringUtils.hasText(current.getRotatedToId())) {
                String candidate = tokenProvider.refreshSuccessor(rawCurrent, rotationNonce);
                if (hashToken(candidate).equals(current.getRotatedToId())) {
                    UserSessionEntity successor = sessionMapper.findRefreshForUpdate(current.getRotatedToId());
                    if (successor != null && successor.getRevokedAt() == null
                            && successor.getRotationRedeemedAt() == null
                            && java.util.Objects.equals(successor.getUserId(), current.getUserId())
                            && StringUtils.hasText(current.getSessionChainId())
                            && current.getSessionChainId().equals(successor.getSessionChainId())) {
                        current = successor;
                        recoveredRaw = candidate;
                    } else {
                        // The same logical rotation already advanced. Never revoke a later live descendant.
                        return ApiResult.fail(409, "USER_REFRESH_ROTATION_SUPERSEDED");
                    }
                }
            }
        }
        if (recoveredRaw == null && (current.getRotationRedeemedAt() != null || current.getRevokedAt() != null)) {
            String chainId = StringUtils.hasText(current.getSessionChainId())
                    ? current.getSessionChainId() : tokenId;
            revocations.revokeOwned(current.getUserId(), current.getRefreshTokenId());
            outboxService.publish("USER_SECURITY", String.valueOf(current.getUserId()),
                    "auth.refresh_token_reuse_detected", Map.of(
                            "targetUserId", current.getUserId(),
                            "sessionChainId", chainId,
                            "detectedAt", LocalDateTime.now().toString()));
            return ApiResult.fail(401, "USER_REFRESH_TOKEN_REUSE_DETECTED");
        }
        if (StringUtils.hasText(rotationNonce) && !nonceRotation) {
            return ApiResult.fail(400, "USER_REFRESH_ROTATION_KEY_INVALID");
        }
        LocalDateTime now = LocalDateTime.now(DateTimeFormatConfig.BUSINESS_ZONE);
        int idleDays = configInt("auth.session.idle_ttl_days", 30, 7, 90);
        LocalDateTime lastActive = current.getLastActiveAt() == null ? current.getCreatedAt() : current.getLastActiveAt();
        if (current.getExpiresAt() == null || !current.getExpiresAt().isAfter(now)
                || lastActive == null || !lastActive.plusDays(idleDays).isAfter(now)) {
            revocations.expireOwned(current.getUserId(), current.getRefreshTokenId());
            return endedRefresh(current, "USER_REFRESH_TOKEN_EXPIRED", "SESSION_EXPIRED");
        }
        UserEntity user = userMapper.selectById(current.getUserId());
        if (user == null || !Integer.valueOf(0).equals(user.getIsDeleted()) || !"ACTIVE".equalsIgnoreCase(user.getStatus())
                || blocklistVerifier.isBlocked(user.getId()) || userMapper.isPasswordResetRequired(user.getId())) {
            revocations.revokeOwned(current.getUserId(), current.getRefreshTokenId());
            return ApiResult.fail(403, "USER_REFRESH_NOT_ALLOWED");
        }
        ApiResult<UserLoginResponse> environmentFailure = environmentFailure(user, true);
        if (environmentFailure != null) {
            revocations.revokeOwned(current.getUserId(), current.getRefreshTokenId());
            return environmentFailure;
        }
        if (!validCountryCode(user.getCountryCode())) {
            revocations.revokeAll(user.getId());
            return ApiResult.fail(403, "USER_REFRESH_COUNTRY_CODE_FORBIDDEN");
        }
        if (!validPhone(user.getCountryCode(), user.getPhone())) {
            revocations.revokeAll(user.getId());
            return ApiResult.fail(403, "USER_REFRESH_PHONE_INVALID");
        }
        if (recoveredRaw != null) {
            String access = tokenProvider.createUserToken(user.getId(), user.getPhone(), List.of(),
                    current.getRefreshTokenId(),
                    Duration.ofHours(configInt("auth.session.access_ttl_hours", 4, 1, 24)),
                    UserAuthEnvironment.resolve(environment)
                            .orElseThrow(() -> new BizException(503, "USER_AUTH_ENVIRONMENT_FORBIDDEN")));
            return ApiResult.ok(new UserLoginResponse(access, "Bearer",
                    new UserLoginResponse.UserSession(user.getId(), normalizeCountryCode(user.getCountryCode()),
                            user.getPhone(), user.getNickname(), userMapper.isOnboardingComplete(user.getId())),
                    null, null, recoveredRaw, null, tokenProvider.sessionSyncKey(current.getSessionChainId())).withContext(current, "ACTIVE"));
        }
        String rawNext = nonceRotation ? tokenProvider.refreshSuccessor(rawCurrent, rotationNonce) : randomRefreshToken();
        String nextId = hashToken(rawNext);
        if (sessionMapper.markRefreshRotated(current.getId(), nextId) != 1) {
            revocations.revokeOwned(current.getUserId(), current.getRefreshTokenId());
            return ApiResult.fail(409, "USER_REFRESH_STATE_CHANGED");
        }
        UserSessionEntity next = new UserSessionEntity();
        next.setUserId(user.getId());
        next.setRefreshTokenId(nextId);
        next.setDeviceName(current.getDeviceName());
        next.setClientIp(current.getClientIp());
        next.setSessionChainId(current.getSessionChainId());
        next.setSessionGroup(current.getSessionGroup());
        next.setInstallationId(current.getInstallationId());
        next.setAppSessionVersion(current.getAppSessionVersion());
        next.setExecutionSupported(current.getExecutionSupported());
        next.setLoginAttemptId(current.getLoginAttemptId());
        next.setLastActiveAt(now);
        next.setExpiresAt(now.plusDays(configInt("auth.session.refresh_ttl_days", 30, 7, 90)));
        next.setIsDeleted(0);
        sessionMapper.insert(next);
        String phone = user.getPhone() == null ? "" : user.getPhone();
        String countryCode = normalizeCountryCode(user.getCountryCode());
        UserAuthEnvironment audience = UserAuthEnvironment.resolve(environment)
                .orElseThrow(() -> new BizException(503, "USER_AUTH_ENVIRONMENT_FORBIDDEN"));
        String access = tokenProvider.createUserToken(
                user.getId(), phone, List.of(), nextId,
                Duration.ofHours(configInt("auth.session.access_ttl_hours", 4, 1, 24)), audience);
        return ApiResult.ok(new UserLoginResponse(access, "Bearer",
                new UserLoginResponse.UserSession(user.getId(), countryCode, phone, user.getNickname(),
                        userMapper.isOnboardingComplete(user.getId())),
                null, null, rawNext, null, tokenProvider.sessionSyncKey(current.getSessionChainId())).withContext(current, "ACTIVE"));
    }

    @Transactional
    public ApiResult<Map<String, Object>> logout(UserRefreshRequest request) {
        if (request == null || !StringUtils.hasText(request.refreshToken())
                || request.refreshToken().trim().length() > 512) {
            return ApiResult.ok(Map.of("revoked", false));
        }
        String tokenId = hashToken(request.refreshToken().trim());
        UserSessionEntity routed = sessionMapper.findSession(tokenId);
        if (routed != null && sessionMapper.lockAccount(routed.getUserId()) == null)
            throw new BizException(403, "USER_AUTH_REQUIRED");
        UserSessionEntity current = routed == null ? null : sessionMapper.findRefreshForUpdate(tokenId);
        if (current == null) {
            return ApiResult.ok(Map.of("revoked", false));
        }
        String chainId = StringUtils.hasText(current.getSessionChainId())
                ? current.getSessionChainId() : tokenId;
        singleSessions.logout(current);
        return ApiResult.ok(Map.of("revoked", true));
    }

    @Transactional
    public ApiResult<Map<String, Object>> discardSession(Authentication authentication) {
        if (authentication == null || !authentication.isAuthenticated()
                || !(authentication.getDetails() instanceof Map<?, ?> details)
                || !"USER".equals(details.get("subjectType"))
                || !(details.get("sessionId") instanceof String sessionId) || sessionId.isBlank())
            throw new BizException(401, "USER_AUTH_REQUIRED");
        long userId;
        try { userId = Long.parseLong(authentication.getName()); }
        catch (NumberFormatException invalid) { throw new BizException(401, "USER_AUTH_REQUIRED"); }
        if (userId <= 0) throw new BizException(401, "USER_AUTH_REQUIRED");
        return ApiResult.ok(Map.of("revoked", revocations.revokeOwned(userId, sessionId) > 0));
    }

    private boolean validRotationNonce(String nonce) {
        return nonce != null && nonce.matches("[0-9a-f]{64}");
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

    private UserEntity lockLoginUser(UserEntity user) {
        if (user == null || sessionMapper.lockAccount(user.getId()) == null) return null;
        return userMapper.findLoginUserForUpdate(user.getId());
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

    private UserOtpLoginChallengeResponse disposableOtpLoginChallenge(String phone, AppOtpPolicy policy) {
        return new UserOtpLoginChallengeResponse(
                "LOGIN-" + UUID.randomUUID().toString().replace("-", ""),
                policy.cooldownSeconds(),
                maskPhone(phone));
    }

    private String maskPhone(String phone) {
        return phone.length() <= 4 ? "****" : "****" + phone.substring(phone.length() - 4);
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

    private OtpSendRateDecision consumeOtpSendRate(String loginKey, LocalDateTime now, AppOtpPolicy policy,
            CaptchaScene scene, String captchaTicket, String clientAddress) {
        int cooldownSeconds = policy.cooldownSeconds();
        int windowMinutes = configInt("auth.risk.otp_send_window_minutes", OTP_SEND_WINDOW_MINUTES, 5, 60);
        int windowLimit = configInt("auth.risk.otp_send_window_limit", OTP_SEND_WINDOW_LIMIT, 2, 20);
        int dayLimit = policy.max24h();
        loginGuardMapper.initializeOtpSendGuard(loginKey, now);
        UserOtpSendGuardRecord guard = loginGuardMapper.lockOtpSendGuard(loginKey);
        if (guard == null) throw new IllegalStateException("USER_OTP_SEND_GUARD_UNAVAILABLE");
        if (guard.getLastSentAt() != null && guard.getLastSentAt().plusSeconds(cooldownSeconds).isAfter(now)) {
            return OtpSendRateDecision.RATE_LIMITED;
        }
        boolean newWindow = guard.getWindowStartedAt() == null
                || !guard.getWindowStartedAt().plusMinutes(windowMinutes).isAfter(now);
        int windowCount = newWindow ? 1 : guard.getWindowSendCount() + 1;
        int legacyCount = guard.getLegacyWindowUntil() != null && guard.getLegacyWindowUntil().isAfter(now)
                ? guard.getDaySendCount() : 0;
        int trailing24hCount = loginGuardMapper.countRecentOtpSendEvents(loginKey, now.minusHours(24));
        if (windowCount > windowLimit || legacyCount + trailing24hCount >= dayLimit) return OtpSendRateDecision.RATE_LIMITED;
        CaptchaOtpGate.Decision captcha = captchaGate.checkAndConsume(scene, captchaTicket, clientAddress, trailing24hCount);
        if (!captcha.allowed()) return "USER_CAPTCHA_VERIFIER_UNAVAILABLE".equals(captcha.code())
                ? OtpSendRateDecision.CAPTCHA_VERIFIER_UNAVAILABLE : OtpSendRateDecision.CAPTCHA_REJECTED;
        if (loginGuardMapper.insertOtpSendEvent(loginKey, now) != 1) {
            throw new IllegalStateException("USER_OTP_SEND_EVENT_CREATE_FAILED");
        }
        LocalDateTime windowStartedAt = newWindow ? now : guard.getWindowStartedAt();
        LocalDateTime dayStartedAt = now;
        if (loginGuardMapper.recordOtpSend(
                loginKey, now, windowStartedAt, windowCount, dayStartedAt, trailing24hCount + 1) != 1) {
            throw new IllegalStateException("USER_OTP_SEND_GUARD_STATE_CHANGED");
        }
        return OtpSendRateDecision.ALLOWED;
    }

    private <T> ApiResult<T> captchaOrRateFailure(OtpSendRateDecision decision) {
        return switch (decision) {
            case CAPTCHA_VERIFIER_UNAVAILABLE -> ApiResult.fail(503, "USER_CAPTCHA_VERIFIER_UNAVAILABLE");
            case CAPTCHA_REJECTED -> ApiResult.fail(428, "USER_CAPTCHA_REQUIRED");
            default -> ApiResult.fail(429, "USER_OTP_SEND_RATE_LIMITED");
        };
    }

    private enum OtpSendRateDecision { ALLOWED, RATE_LIMITED, CAPTCHA_REJECTED, CAPTCHA_VERIFIER_UNAVAILABLE }

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

    private String otpSendKey(String countryCode, String phone, String namespace) {
        return OtpPhoneRateLimitKey.from(namespace, countryCode, phone);
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
