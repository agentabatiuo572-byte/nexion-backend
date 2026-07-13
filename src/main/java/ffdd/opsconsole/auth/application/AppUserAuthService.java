package ffdd.opsconsole.auth.application;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import ffdd.opsconsole.auth.dto.UserLoginRequest;
import ffdd.opsconsole.auth.dto.UserLoginResponse;
import ffdd.opsconsole.auth.infrastructure.UserLoginGuardRecord;
import ffdd.opsconsole.auth.mapper.UserLoginGuardMapper;
import ffdd.opsconsole.shared.api.ApiResult;
import ffdd.opsconsole.shared.security.JwtProperties;
import ffdd.opsconsole.shared.security.JwtTokenProvider;
import ffdd.opsconsole.shared.security.infrastructure.UserSessionEntity;
import ffdd.opsconsole.shared.security.mapper.AuthSessionMapper;
import ffdd.opsconsole.user.infrastructure.UserEntity;
import ffdd.opsconsole.user.mapper.UserOpsMapper;
import java.time.LocalDateTime;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.List;
import java.util.UUID;
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

    @PostConstruct
    void ensureLoginGuardSchema() {
        loginGuardMapper.createTable();
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
        if (guard != null && guard.getLockedUntil() != null && guard.getLockedUntil().isAfter(now)) {
            return ApiResult.fail(429, "USER_LOGIN_TEMPORARILY_LOCKED");
        }
        UserEntity user = userMapper.selectOne(new LambdaQueryWrapper<UserEntity>()
                .in(UserEntity::getCountryCode, countryCode, countryCode.substring(1))
                .eq(UserEntity::getPhone, phone)
                .eq(UserEntity::getIsDeleted, 0)
                .last("LIMIT 1"));
        boolean passwordMatches = safePasswordMatches(request.password(),
                user != null && StringUtils.hasText(user.getPasswordHash()) ? user.getPasswordHash() : DUMMY_PASSWORD_HASH);
        if (user == null || !"ACTIVE".equalsIgnoreCase(user.getStatus()) || !passwordMatches) {
            recordFailure(loginKey, guard, now);
            return invalidCredential();
        }

        loginGuardMapper.clear(loginKey);

        String sessionId = UUID.randomUUID().toString();
        UserSessionEntity session = new UserSessionEntity();
        session.setUserId(user.getId());
        session.setRefreshTokenId(sessionId);
        session.setDeviceName("nexion-app");
        session.setExpiresAt(LocalDateTime.now().plusMinutes(jwtProperties.getTtlMinutes()));
        session.setIsDeleted(0);
        sessionMapper.insert(session);

        String token = tokenProvider.createToken(user.getId(), "USER", phone, List.of(), sessionId);
        return ApiResult.ok(new UserLoginResponse(token, "Bearer",
                new UserLoginResponse.UserSession(user.getId(), countryCode, phone, user.getNickname())));
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

    private void recordFailure(String loginKey, UserLoginGuardRecord guard, LocalDateTime now) {
        boolean expiredWindow = guard == null || guard.getWindowStartedAt() == null
                || guard.getWindowStartedAt().plusMinutes(WINDOW_MINUTES).isBefore(now);
        int failedCount = expiredWindow ? 1 : guard.getFailedCount() + 1;
        LocalDateTime windowStartedAt = expiredWindow ? now : guard.getWindowStartedAt();
        LocalDateTime lockedUntil = failedCount >= MAX_FAILURES ? now.plusMinutes(WINDOW_MINUTES) : null;
        loginGuardMapper.recordFailure(loginKey, failedCount, windowStartedAt, lockedUntil);
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
}
