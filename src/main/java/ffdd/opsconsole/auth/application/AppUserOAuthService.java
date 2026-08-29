package ffdd.opsconsole.auth.application;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import ffdd.opsconsole.auth.dto.UserLoginResponse;
import ffdd.opsconsole.auth.dto.UserOAuthExchangeRequest;
import ffdd.opsconsole.auth.dto.UserOAuthExchangeResponse;
import ffdd.opsconsole.auth.infrastructure.UserOAuthIdentityEntity;
import ffdd.opsconsole.auth.mapper.UserOAuthIdentityMapper;
import ffdd.opsconsole.shared.api.ApiResult;
import ffdd.opsconsole.shared.outbox.EventOutboxService;
import ffdd.opsconsole.shared.security.UserAuthEnvironment;
import ffdd.opsconsole.user.infrastructure.UserEntity;
import ffdd.opsconsole.user.mapper.UserOpsMapper;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import org.springframework.core.env.Environment;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

@Service
@RequiredArgsConstructor
public class AppUserOAuthService {
    private static final String GOOGLE = "GOOGLE";
    private static final String APPLE = "APPLE";
    private static final String PASSKEY = "PASSKEY";
    private static final String TELEGRAM = "TELEGRAM";
    private static final String DEVELOPMENT_PASSKEY_COUNTRY_CODE = "+86";
    private static final String DEVELOPMENT_PASSKEY_PHONE = "18708173775";
    private final UserOAuthIdentityMapper identityMapper;
    private final UserOpsMapper userMapper;
    private final PasswordEncoder passwordEncoder;
    private final AppUserAuthService authService;
    private final EventOutboxService outboxService;
    private final Environment environment;
    private final OAuthSandboxChallengeService sandboxChallengeService;

    @PostConstruct
    void ensureSchema() {
        identityMapper.createTable();
    }

    @Transactional
    public ApiResult<UserOAuthExchangeResponse> exchange(
            UserOAuthExchangeRequest request, String clientAddress, String requestOrigin) {
        var resolved = UserAuthEnvironment.resolve(environment);
        if (resolved.isEmpty()) return ApiResult.fail(503, "OAUTH_PROFILE_FORBIDDEN");
        UserAuthEnvironment authEnvironment = resolved.get();
        if (request == null || !StringUtils.hasText(request.provider())) {
            return ApiResult.fail(422, "OAUTH_REQUEST_INVALID");
        }
        String provider = request.provider().trim().toUpperCase(Locale.ROOT);
        boolean developmentPasskey = UserAuthEnvironment.hasSingleActiveProfile(environment, "dev")
                && PASSKEY.equals(provider);
        if (!validRequest(request, authEnvironment, developmentPasskey)) {
            return ApiResult.fail(422, "OAUTH_REQUEST_INVALID");
        }
        if (authEnvironment == UserAuthEnvironment.PRODUCTION && !developmentPasskey) {
            if (!providerConfigured(provider)) return ApiResult.fail(503, "OAUTH_PROVIDER_NOT_CONFIGURED");
            // A configured provider still needs a verified exchange adapter. Never trust
            // an arbitrary client subject and never silently fall back to the sandbox.
            return ApiResult.fail(503, "OAUTH_PROVIDER_UNAVAILABLE");
        }
        if (!UserAuthEnvironment.hasSafeDevelopmentForwardHeaderPolicy(environment)) {
            return ApiResult.fail(503, "OAUTH_DEVELOPMENT_PASSKEY_NETWORK_POLICY_INVALID");
        }
        if (!UserAuthEnvironment.isLocalDevelopmentRequest(clientAddress, requestOrigin)) {
            return ApiResult.fail(403, "OAUTH_DEVELOPMENT_PASSKEY_LOCAL_ONLY");
        }
        String sourceEnvironment = authEnvironment.name();
        String subject = sandboxChallengeService.consume(provider, request.challengeNo()).orElse(null);
        if (!StringUtils.hasText(subject)) {
            return ApiResult.fail(401, developmentPasskey
                    ? "OAUTH_DEVELOPMENT_CHALLENGE_INVALID"
                    : "OAUTH_SANDBOX_CHALLENGE_INVALID");
        }
        if (PASSKEY.equals(provider)) {
            UserEntity developmentUser = developmentPasskeyAccount(authEnvironment);
            if (!isActiveInEnvironment(developmentUser, authEnvironment)) {
                return ApiResult.fail(503, "OAUTH_DEVELOPMENT_ACCOUNT_NOT_FOUND");
            }
            return issueDevelopmentSession(developmentUser, provider, subject, clientAddress,
                    false, authEnvironment == UserAuthEnvironment.SANDBOX);
        }
        UserOAuthIdentityEntity identity = identityMapper.findForUpdate(provider, subject, sourceEnvironment);
        UserEntity user;
        boolean created = false;
        if (identity != null) {
            user = userMapper.selectById(identity.getUserId());
            if (!isActiveInEnvironment(user, authEnvironment)) return ApiResult.fail(403, "OAUTH_IDENTITY_NOT_AVAILABLE");
        } else {
            user = createSandboxUser(provider, subject, request.displayName(), clientAddress);
            userMapper.insert(user);
            if (user.getId() == null) throw new IllegalStateException("OAUTH_SANDBOX_USER_ID_MISSING");
            userMapper.ensureRegisteredUserWallet(user.getId(), 1);
            identity = new UserOAuthIdentityEntity();
            identity.setProvider(provider);
            identity.setExternalSubject(subject);
            identity.setUserId(user.getId());
            identity.setSourceEnvironment(sourceEnvironment);
            identity.setDisplayName(displayName(provider, request.displayName()));
            try {
                identityMapper.insertIdentity(identity);
                created = true;
            } catch (DuplicateKeyException duplicate) {
                // Another transaction won the provider/subject unique key. The
                // loser has already created a provisional sandbox user/wallet;
                // reload the winner with a row lock, then tombstone only the
                // losing rows before issuing the winner's real session.
                UserOAuthIdentityEntity winner = identityMapper.findForUpdate(
                        provider, subject, sourceEnvironment);
                if (winner == null) throw duplicate;
                int walletDeleted = userMapper.softDeleteSandboxOAuthWallet(user.getId());
                int userDeleted = userMapper.softDeleteSandboxOAuthUser(user.getId());
                if (walletDeleted != 1 || userDeleted != 1) {
                    throw new IllegalStateException("OAUTH_CONFLICT_CLEANUP_FAILED");
                }
                identity = winner;
                user = userMapper.selectById(identity.getUserId());
                if (!isActiveInEnvironment(user, authEnvironment)) return ApiResult.fail(403, "OAUTH_IDENTITY_NOT_AVAILABLE");
            }
        }
        return issueDevelopmentSession(user, provider, subject, clientAddress, created, true);
    }

    private ApiResult<UserOAuthExchangeResponse> issueDevelopmentSession(
            UserEntity user, String provider, String subject, String clientAddress, boolean created, boolean sandbox) {
        ApiResult<UserLoginResponse> session = authService.issueRegisteredSession(user, clientAddress);
        if (session.getCode() != 0 || session.getData() == null) {
            return ApiResult.fail(session.getCode(), session.getMessage());
        }
        UserLoginResponse login = session.getData();
        String source = sandbox ? "mock" : "development";
        outboxService.publish("USER_SECURITY", String.valueOf(user.getId()),
                created ? "auth.oauth_sandbox_account_created"
                        : sandbox ? "auth.oauth_sandbox_login" : "auth.development_passkey_login",
                Map.of("userId", user.getId(), "provider", provider, "source", source, "sandbox", sandbox,
                        "subjectHash", hashSubject(provider + ":" + subject)));
        return ApiResult.ok(new UserOAuthExchangeResponse(login.accessToken(), login.tokenType(), login.user(),
                login.refreshToken(), source, sandbox));
    }

    private UserEntity developmentPasskeyAccount(UserAuthEnvironment authEnvironment) {
        String countryCode = environment.getProperty(
                "nexion.auth.development-passkey-account.country-code");
        String phone = environment.getProperty("nexion.auth.development-passkey-account.phone");
        if (!StringUtils.hasText(countryCode) || !StringUtils.hasText(phone)) return null;
        String normalizedCountryCode = countryCode.trim();
        if (!normalizedCountryCode.startsWith("+")) normalizedCountryCode = "+" + normalizedCountryCode;
        String normalizedPhone = phone.trim();
        if (!DEVELOPMENT_PASSKEY_COUNTRY_CODE.equals(normalizedCountryCode)
                || !DEVELOPMENT_PASSKEY_PHONE.equals(normalizedPhone)) return null;
        if (authEnvironment == UserAuthEnvironment.SANDBOX) {
            return userMapper.lockActiveDevelopmentUserByPhone(
                    DEVELOPMENT_PASSKEY_COUNTRY_CODE,
                    DEVELOPMENT_PASSKEY_COUNTRY_CODE.substring(1),
                    DEVELOPMENT_PASSKEY_PHONE);
        }
        return userMapper.lockActiveCanonicalDevelopmentUserByPhone(
                DEVELOPMENT_PASSKEY_COUNTRY_CODE,
                DEVELOPMENT_PASSKEY_COUNTRY_CODE.substring(1),
                DEVELOPMENT_PASSKEY_PHONE);
    }

    private boolean validRequest(
            UserOAuthExchangeRequest request, UserAuthEnvironment authEnvironment, boolean developmentPasskey) {
        if (request == null || !StringUtils.hasText(request.provider())) {
            return false;
        }
        String provider = request.provider().trim().toUpperCase(Locale.ROOT);
        if (!Set.of(GOOGLE, APPLE, PASSKEY, TELEGRAM).contains(provider)) return false;
        if (authEnvironment == UserAuthEnvironment.PRODUCTION && !developmentPasskey) return true;
        return StringUtils.hasText(request.challengeNo())
                && request.challengeNo().trim().matches("OAUTH-[a-f0-9]{32}");
    }

    private boolean providerConfigured(String provider) {
        String prefix = provider.toLowerCase(Locale.ROOT);
        // Presence checks deliberately avoid reading or logging credential values.
        if (PASSKEY.equals(provider)) {
            return environment.containsProperty("oauth.passkey.rp-id")
                    && environment.containsProperty("oauth.passkey.origin");
        }
        if (TELEGRAM.equals(provider)) {
            return environment.containsProperty("oauth.telegram.bot-token");
        }
        return environment.containsProperty("oauth." + prefix + ".client-id")
                && environment.containsProperty("oauth." + prefix + ".client-secret");
    }

    private UserEntity createSandboxUser(String provider, String subject, String requestedName, String clientAddress) {
        String key = provider + ":" + subject;
        String digits = hashSubject(key).replaceAll("[^0-9]", "");
        String phone = "900" + (digits + "000000000000").substring(0, 12);
        UserEntity user = new UserEntity();
        user.setCountryCode("+1");
        user.setPhone(phone);
        user.setClientIp(StringUtils.hasText(clientAddress) ? clientAddress.trim() : null);
        user.setPasswordHash(passwordEncoder.encode(UUID.randomUUID().toString()));
        user.setNickname(displayName(provider, requestedName));
        user.setReferralCode("OAUTH-" + hashSubject(key).substring(0, 12).toUpperCase(Locale.ROOT));
        user.setUserLevel("L0");
        user.setVRank("V0");
        user.setStatus("ACTIVE");
        user.setSandbox(1);
        user.setIsDeleted(0);
        return user;
    }

    private boolean isActiveInEnvironment(UserEntity user, UserAuthEnvironment authEnvironment) {
        return user != null && authEnvironment.acceptsSandbox(user.getSandbox())
                && Integer.valueOf(0).equals(user.getIsDeleted()) && "ACTIVE".equalsIgnoreCase(user.getStatus());
    }

    private String displayName(String provider, String value) {
        String safe = value == null ? "" : value.replaceAll("[\\p{Cntrl}]", "").trim();
        if (safe.length() > 64) safe = safe.substring(0, 64);
        return StringUtils.hasText(safe) ? safe : provider + " Sandbox";
    }

    private String hashSubject(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("OAUTH_SUBJECT_HASH_UNAVAILABLE", exception);
        }
    }
}
