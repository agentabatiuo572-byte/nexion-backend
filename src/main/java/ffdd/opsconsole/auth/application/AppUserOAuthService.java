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
import java.util.UUID;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import org.springframework.core.env.Environment;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

@Service
@RequiredArgsConstructor
public class AppUserOAuthService {
    private static final String GOOGLE = "GOOGLE";
    private static final String APPLE = "APPLE";
    private static final String SANDBOX_MOCK = "SANDBOX_MOCK";
    private final UserOAuthIdentityMapper identityMapper;
    private final UserOpsMapper userMapper;
    private final PasswordEncoder passwordEncoder;
    private final AppUserAuthService authService;
    private final EventOutboxService outboxService;
    private final Environment environment;

    @PostConstruct
    void ensureSchema() {
        identityMapper.createTable();
    }

    @Transactional
    public ApiResult<UserOAuthExchangeResponse> exchange(UserOAuthExchangeRequest request, String clientAddress) {
        if (!validRequest(request)) return ApiResult.fail(422, "OAUTH_REQUEST_INVALID");
        var resolved = UserAuthEnvironment.resolve(environment);
        if (resolved.isEmpty()) return ApiResult.fail(503, "OAUTH_PROFILE_FORBIDDEN");
        UserAuthEnvironment authEnvironment = resolved.get();
        String provider = request.provider().trim().toUpperCase(Locale.ROOT);
        String mode = request.mode().trim().toUpperCase(Locale.ROOT);
        if (authEnvironment == UserAuthEnvironment.PRODUCTION) {
            if (SANDBOX_MOCK.equals(mode)) return ApiResult.fail(503, "OAUTH_PROVIDER_NOT_CONFIGURED");
            if (!providerConfigured(provider)) return ApiResult.fail(503, "OAUTH_PROVIDER_NOT_CONFIGURED");
            // A configured provider still needs a verified exchange adapter. Never trust
            // an arbitrary client subject and never silently fall back to the sandbox.
            return ApiResult.fail(503, "OAUTH_PROVIDER_UNAVAILABLE");
        }
        if (!SANDBOX_MOCK.equals(mode)) return ApiResult.fail(503, "OAUTH_SANDBOX_ONLY");
        String sourceEnvironment = authEnvironment.name();
        String subject = request.externalSubject().trim();
        UserOAuthIdentityEntity identity = identityMapper.findForUpdate(provider, subject, sourceEnvironment);
        UserEntity user;
        boolean created = false;
        if (identity != null) {
            user = userMapper.selectById(identity.getUserId());
            if (!isSandboxActive(user)) return ApiResult.fail(403, "OAUTH_IDENTITY_NOT_AVAILABLE");
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
            identityMapper.insertIdentity(identity);
            created = true;
        }
        ApiResult<UserLoginResponse> session = authService.issueRegisteredSession(user, clientAddress);
        if (session.getCode() != 0 || session.getData() == null) {
            return ApiResult.fail(session.getCode(), session.getMessage());
        }
        UserLoginResponse login = session.getData();
        outboxService.publish("USER_SECURITY", String.valueOf(user.getId()),
                created ? "auth.oauth_sandbox_account_created" : "auth.oauth_sandbox_login",
                Map.of("userId", user.getId(), "provider", provider, "source", "mock", "sandbox", true,
                        "subjectHash", hashSubject(provider + ":" + subject)));
        return ApiResult.ok(new UserOAuthExchangeResponse(login.accessToken(), login.tokenType(), login.user(),
                login.refreshToken(), "mock", true));
    }

    private boolean validRequest(UserOAuthExchangeRequest request) {
        if (request == null || !StringUtils.hasText(request.provider()) || !StringUtils.hasText(request.mode())
                || !StringUtils.hasText(request.externalSubject())) return false;
        String provider = request.provider().trim().toUpperCase(Locale.ROOT);
        String mode = request.mode().trim().toUpperCase(Locale.ROOT);
        return (GOOGLE.equals(provider) || APPLE.equals(provider))
                && (SANDBOX_MOCK.equals(mode) || "PROVIDER".equals(mode))
                && request.externalSubject().trim().matches("[A-Za-z0-9._:-]{1,128}");
    }

    private boolean providerConfigured(String provider) {
        String prefix = provider.toLowerCase(Locale.ROOT);
        // Presence checks deliberately avoid reading or logging credential values.
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

    private boolean isSandboxActive(UserEntity user) {
        return user != null && Integer.valueOf(1).equals(user.getSandbox())
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
