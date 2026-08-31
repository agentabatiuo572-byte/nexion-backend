package ffdd.opsconsole.auth.application;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import ffdd.opsconsole.auth.dto.UserLoginResponse;
import ffdd.opsconsole.auth.dto.UserRegistrationOtpRequest;
import ffdd.opsconsole.auth.dto.UserRegistrationOtpResponse;
import ffdd.opsconsole.auth.dto.UserRegistrationRequest;
import ffdd.opsconsole.growth.application.OpsReferralRewardService;
import ffdd.opsconsole.growth.domain.ReferralRewardPublicConfigView;
import ffdd.opsconsole.auth.mapper.AppUserRegistrationMapper;
import ffdd.opsconsole.auth.mapper.TeamAncestorProjection;
import ffdd.opsconsole.shared.api.ApiResult;
import ffdd.opsconsole.shared.exception.BizException;
import ffdd.opsconsole.shared.outbox.EventOutboxService;
import ffdd.opsconsole.shared.security.UserAuthEnvironment;
import ffdd.opsconsole.user.infrastructure.UserEntity;
import ffdd.opsconsole.user.mapper.UserOpsMapper;
import jakarta.annotation.PostConstruct;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.math.BigDecimal;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.PessimisticLockingFailureException;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.core.env.Environment;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

@Service
@RequiredArgsConstructor
@Slf4j
public class AppUserRegistrationService {
    private static final int OTP_TTL_MINUTES = 5;
    private static final int RESEND_AFTER_SECONDS = 60;
    private static final String K1_MAX_SIGNUP_PER_IP_24H = "maxSignupPerIp24h";
    private static final Set<String> WEAK_PASSWORDS = Set.of(
            "12345678", "123456789", "1234567890", "password", "password1",
            "passw0rd", "qwerty123", "qwertyuiop", "iloveyou", "admin123",
            "welcome1", "letmein1", "11111111", "abcdefgh", "12341234", "asdfghjk");
    private final AppUserRegistrationMapper mapper;
    private final UserOpsMapper userMapper;
    private final PasswordEncoder passwordEncoder;
    private final UserOtpDeliveryService otpDeliveryService;
    private final AppUserAuthService authService;
    private final EventOutboxService outboxService;
    private final AppUserRegistrationTransactionExecutor transactionExecutor;
    private final Environment environment;
    private final OpsReferralRewardService referralRewardService;

    @PostConstruct
    void ensureSchema() {
        mapper.createTable();
    }

    @Transactional
    public ApiResult<UserRegistrationOtpResponse> sendOtp(
            UserRegistrationOtpRequest request,
            String clientAddress) {
        if (request == null || !validCountryCode(request.countryCode()) || !validPhone(request.phone())) {
            return ApiResult.fail(422, "USER_REGISTRATION_PHONE_INVALID");
        }
        UserAuthEnvironment audience = UserAuthEnvironment.resolve(environment).orElse(null);
        if (audience == null) return ApiResult.fail(503, "USER_REGISTRATION_PROFILE_FORBIDDEN");
        if (!otpDeliveryService.available()) {
            return ApiResult.fail(503, "USER_OTP_DELIVERY_UNAVAILABLE");
        }
        String countryCode = normalizeCountryCode(request.countryCode());
        String phone = request.phone().trim();
        String clientIp = normalizeClientAddress(clientAddress);
        if (mapper.countRecentClient(clientIp) >= 10 || mapper.countDailyClient(clientIp) >= 100) {
            return ApiResult.fail(429, "USER_REGISTRATION_OTP_CLIENT_RATE_LIMIT");
        }
        if (mapper.countRecentPhoneInEnvironment(countryCode, phone, audience.name()) > 0) {
            return ApiResult.fail(429, "USER_REGISTRATION_OTP_COOLDOWN");
        }
        if (mapper.countDailyPhoneInEnvironment(countryCode, phone, audience.name()) >= 10) {
            return ApiResult.fail(429, "USER_REGISTRATION_OTP_DAILY_LIMIT");
        }
        mapper.invalidateActiveInEnvironment(countryCode, phone, audience.name());
        String challengeNo = "REG-" + UUID.randomUUID().toString().replace("-", "");
        String code = otpDeliveryService.verificationCode();
        if (mapper.insertChallengeInEnvironment(
                challengeNo, countryCode, phone, clientIp, audience.name(), code, OTP_TTL_MINUTES) != 1) {
            throw new IllegalStateException("USER_REGISTRATION_OTP_CREATE_FAILED");
        }
        try {
            otpDeliveryService.deliver(
                    countryCode, phone, challengeNo, code, OTP_TTL_MINUTES);
        } catch (RuntimeException exception) {
            throw new BizException(503, "USER_OTP_DELIVERY_FAILED");
        }
        return ApiResult.ok(new UserRegistrationOtpResponse(
                challengeNo,
                RESEND_AFTER_SECONDS,
                maskPhone(phone)));
    }

    public ApiResult<UserLoginResponse> register(
            UserRegistrationRequest request,
            String clientAddress) {
        Integer sandbox = sandboxForNewRegistration();
        if (sandbox == null) {
            return ApiResult.fail(503, "USER_REGISTRATION_PROFILE_FORBIDDEN");
        }
        for (int attempt = 1; attempt <= 3; attempt++) {
            try {
                return transactionExecutor.execute(() -> registerInTransaction(request, clientAddress, sandbox));
            } catch (PessimisticLockingFailureException exception) {
                log.warn("Registration lock conflict; rolled back attempt {}/3 before retry", attempt);
            }
        }
        return ApiResult.fail(503, "USER_REGISTRATION_RETRYABLE_CONFLICT");
    }

    private ApiResult<UserLoginResponse> registerInTransaction(
            UserRegistrationRequest request,
            String clientAddress,
            int sandbox) {
        if (request == null
                || !validCountryCode(request.countryCode())
                || !validPhone(request.phone())
                || !StringUtils.hasText(request.challengeNo())
                || !StringUtils.hasText(request.code())
                || !request.code().trim().matches("\\d{6}")
                || !validPassword(request.password(), request.phone())) {
            return ApiResult.fail(422, "USER_REGISTRATION_REQUEST_INVALID");
        }
        String countryCode = normalizeCountryCode(request.countryCode());
        String phone = request.phone().trim();
        String challengeNo = request.challengeNo().trim();
        String code = request.code().trim();
        UserAuthEnvironment audience = UserAuthEnvironment.resolve(environment).orElse(null);
        if (audience == null) return ApiResult.fail(503, "USER_REGISTRATION_PROFILE_FORBIDDEN");
        if (mapper.consumeValidChallengeInEnvironment(challengeNo, countryCode, phone, audience.name(), code) != 1) {
            mapper.recordInvalidAttemptInEnvironment(challengeNo, countryCode, phone, audience.name());
            return ApiResult.fail(422, "USER_REGISTRATION_OTP_INVALID");
        }
        UserEntity existing = findUser(countryCode, phone, sandbox);
        if (existing != null) {
            return ApiResult.fail(409, "USER_REGISTRATION_ACCOUNT_EXISTS");
        }
        String registrationIp = mapper.consumedChallengeClientIpInEnvironment(
                challengeNo, countryCode, phone, audience.name());
        if (!StringUtils.hasText(registrationIp)
                || "unknown".equalsIgnoreCase(registrationIp.trim())) {
            throw new BizException(503, "USER_REGISTRATION_CLIENT_IP_UNAVAILABLE");
        }
        // Production keeps the K1 anti-farm ceiling. Strict isolated profiles
        // already write only sandbox users/wallets and all local browsers share
        // the same loopback address, so applying the production 24h IP quota
        // would permanently block repeat registration acceptance on a PC.
        // OTP per-IP/per-phone rate limits above remain active in both modes.
        if (sandbox == 0) {
            int maxSignupPerIp24h = requiredK1MaxSignupPerIp24h();
            if (mapper.countRegisteredAccountsByClientIp24hInEnvironment(
                    registrationIp.trim(), audience.name(), sandbox) >= maxSignupPerIp24h) {
                return ApiResult.fail(409, "USER_REGISTRATION_K1_IP_LIMIT");
            }
        }

        UserEntity sponsor = null;
        if (StringUtils.hasText(request.sponsorCode())) {
            String sponsorCode = canonicalReferralCode(request.sponsorCode());
            if (!sponsorCode.matches("[A-Z0-9]{4,32}")) {
                return ApiResult.fail(422, "USER_REGISTRATION_SPONSOR_INVALID");
            }
            List<UserEntity> candidates = mapper.findActiveSponsorsByCanonicalCode(sponsorCode);
            if (candidates == null || candidates.isEmpty()) {
                return ApiResult.fail(422, "USER_REGISTRATION_SPONSOR_NOT_FOUND");
            }
            if (candidates.size() != 1) {
                return ApiResult.fail(409, "USER_REGISTRATION_SPONSOR_AMBIGUOUS");
            }
            String storedSponsorCode = candidates.get(0).getReferralCode();
            if (!StringUtils.hasText(storedSponsorCode)
                    || !sponsorCode.equals(canonicalReferralCode(storedSponsorCode))) {
                return ApiResult.fail(409, "USER_REGISTRATION_SPONSOR_STATE_CHANGED");
            }
            sponsor = mapper.findSponsorForUpdate(storedSponsorCode);
            if (sponsor == null) {
                return ApiResult.fail(409, "USER_REGISTRATION_SPONSOR_STATE_CHANGED");
            }
        }
        // A referral is optional. When supplied, it must stay inside the same
        // production/sandbox identity graph. This check precedes every user
        // and wallet write, so a rejected cross-environment request leaves no
        // partially created account or funding surface behind.
        if (sponsor != null && sandbox != sandboxOf(sponsor)) {
            return ApiResult.fail(409, "USER_REGISTRATION_SPONSOR_ENVIRONMENT_MISMATCH");
        }

        UserEntity user = new UserEntity();
        user.setCountryCode(countryCode);
        user.setPhone(phone);
        user.setClientIp(registrationIp.trim());
        user.setPasswordHash(passwordEncoder.encode(request.password()));
        user.setNickname("Nexion " + phone.substring(Math.max(0, phone.length() - 4)));
        user.setReferralCode(nextReferralCode());
        user.setSponsorUserId(sponsor == null ? null : sponsor.getId());
        user.setSponsorCode(sponsor == null ? null : sponsor.getReferralCode());
        user.setUserLevel("L1");
        user.setVRank("V0");
        user.setStatus("ACTIVE");
        user.setSandbox(sandbox);
        user.setLanguage("en-US");
        user.setIsDeleted(0);
        try {
            if (userMapper.insert(user) != 1 || user.getId() == null) {
                throw new IllegalStateException("USER_REGISTRATION_INSERT_FAILED");
            }
        } catch (DuplicateKeyException exception) {
            throw new BizException(409, "USER_REGISTRATION_IDENTITY_CONFLICT");
        }
        createTeamGraphProjection(user.getId(), sandbox);
        userMapper.resetLoginFailures(user.getId());
        userMapper.ensureRegisteredUserWallet(user.getId(), sandbox);
        outboxService.publish(
                "USER_REGISTRATION",
                String.valueOf(user.getId()),
                "auth.register_completed",
                Map.of("userId", user.getId()));
        if (sponsor != null) {
            outboxService.publish(
                    "USER_REFERRAL",
                    String.valueOf(user.getId()),
                    "referral.bound",
                    Map.of(
                            "userId", user.getId(),
                            "sponsorUserId", sponsor.getId(),
                            "source", "nx_user.sponsor_user_id"));
        }
        ApiResult<UserLoginResponse> session = authService.issueRegisteredSession(user, clientAddress);
        if (session.getData() == null || sponsor == null) return session;
        return new ApiResult<>(session.getCode(), session.getMessage(),
                session.getData().withRegistrationReceipt(registrationReceipt(sponsor, sandbox)));
    }

    private void createTeamGraphProjection(Long memberUserId, int sandbox) {
        if (mapper.insertTeamMemberProjection(memberUserId, memberUserId, 0, sandbox) != 1) {
            throw new IllegalStateException("USER_TEAM_SELF_PROJECTION_INSERT_FAILED");
        }
        List<TeamAncestorProjection> ancestors = mapper.listActiveSponsorChain(memberUserId, sandbox);
        if (ancestors == null) {
            return;
        }
        for (TeamAncestorProjection ancestor : ancestors) {
            if (ancestor == null || ancestor.getOwnerUserId() == null || ancestor.getLevel() == null
                    || ancestor.getLevel() < 1 || ancestor.getLevel() > 7) {
                throw new IllegalStateException("USER_TEAM_ANCESTOR_PROJECTION_INVALID");
            }
            if (mapper.insertTeamMemberProjection(
                    ancestor.getOwnerUserId(), memberUserId, ancestor.getLevel(), sandbox) != 1) {
                throw new IllegalStateException("USER_TEAM_ANCESTOR_PROJECTION_INSERT_FAILED");
            }
        }
    }

    private UserEntity findUser(String countryCode, String phone, int sandbox) {
        return userMapper.selectOne(new LambdaQueryWrapper<UserEntity>()
                .in(UserEntity::getCountryCode, countryCode, countryCode.substring(1))
                .eq(UserEntity::getPhone, phone)
                .eq(UserEntity::getSandbox, sandbox)
                .eq(UserEntity::getIsDeleted, 0)
                .last("LIMIT 1"));
    }

    private int sandboxOf(UserEntity user) {
        return user != null && Integer.valueOf(1).equals(user.getSandbox()) ? 1 : 0;
    }

    /** Use the same server-owned account rail as login, refresh, and JWT validation. */
    private Integer sandboxForNewRegistration() {
        return UserAuthEnvironment.resolve(environment)
                .map(audience -> audience == UserAuthEnvironment.SANDBOX ? 1 : 0)
                .orElse(null);
    }

    private String nextReferralCode() {
        return "NX" + UUID.randomUUID().toString()
                .replace("-", "")
                .substring(0, 12)
                .toUpperCase(Locale.ROOT);
    }

    private UserLoginResponse.RegistrationReceipt registrationReceipt(UserEntity sponsor, int sandbox) {
        String sourceEnvironment = sandbox == 1 ? "SANDBOX" : "PRODUCTION";
        BigDecimal giftUsdt = null;
        BigDecimal giftNex = null;
        String status = "UNAVAILABLE";
        if (referralRewardService != null) {
            try {
                ReferralRewardPublicConfigView config = referralRewardService.publicConfig();
                if (config != null && config.welcomeGift() != null
                        && config.welcomeGift().usdtAmount() != null
                        && config.welcomeGift().nexAmount() != null) {
                    giftUsdt = config.welcomeGift().usdtAmount();
                    giftNex = config.welcomeGift().nexAmount();
                    status = "PENDING_REVIEW";
                }
            } catch (RuntimeException ignored) {
                // Registration remains committed; the UI must fail closed when the
                // optional display projection is unavailable.
            }
        }
        return new UserLoginResponse.RegistrationReceipt(
                canonicalReferralCode(sponsor.getReferralCode()),
                maskSponsorName(sponsor.getNickname()), sourceEnvironment, status, giftUsdt, giftNex);
    }

    private String maskSponsorName(String value) {
        if (value == null || value.isBlank()) return "N•••";
        int first = value.codePointAt(0);
        return new String(Character.toChars(first)) + "•••";
    }

    /**
     * Public links may visually group a referral code with hyphens, while the
     * database stores the canonical uppercase unique-key value. Normalize once
     * before the transaction acquires the sponsor row so the lookup remains a
     * single unique-index record lock rather than an expression range scan.
     */
    private String canonicalReferralCode(String value) {
        return value == null ? "" : value.trim().replace("-", "").toUpperCase(Locale.ROOT);
    }

    private boolean validPassword(String password, String phone) {
        if (!StringUtils.hasText(password) || password.length() < 8 || password.length() > 64) return false;
        if (!password.matches(".*[A-Za-z].*") || !password.matches(".*\\d.*")) return false;
        if (password.matches(".*(.)\\1{5,}.*")) return false;
        if (WEAK_PASSWORDS.contains(password.toLowerCase(Locale.ROOT))) return false;
        String normalizedPhone = phone == null ? "" : phone.replace(" ", "");
        return normalizedPhone.length() < 6 || !password.contains(normalizedPhone);
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

    private String normalizeClientAddress(String value) {
        if (!StringUtils.hasText(value)) return "unknown";
        String normalized = value.trim();
        return normalized.length() <= 64 ? normalized : normalized.substring(0, 64);
    }

    private int requiredK1MaxSignupPerIp24h() {
        String configured = mapper.k1ParamValueForUpdate(K1_MAX_SIGNUP_PER_IP_24H);
        if (!StringUtils.hasText(configured)) {
            throw new BizException(503, "USER_REGISTRATION_K1_CONFIG_UNAVAILABLE");
        }
        try {
            int value = Integer.parseInt(configured.trim());
            if (value < 1 || value > 10) {
                throw new BizException(503, "USER_REGISTRATION_K1_CONFIG_UNAVAILABLE");
            }
            return value;
        } catch (NumberFormatException exception) {
            throw new BizException(503, "USER_REGISTRATION_K1_CONFIG_UNAVAILABLE");
        }
    }
}
