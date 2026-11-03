package ffdd.opsconsole.auth.application;

import ffdd.opsconsole.platform.facade.PlatformConfigFacade;
import ffdd.opsconsole.auth.captcha.CaptchaOtpGate;
import ffdd.opsconsole.auth.captcha.CaptchaScene;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import ffdd.opsconsole.auth.dto.UserLoginResponse;
import ffdd.opsconsole.auth.dto.UserOtpLoginVerifyRequest;
import ffdd.opsconsole.auth.dto.UserRegistrationOtpRequest;
import ffdd.opsconsole.auth.dto.UserRegistrationOtpResponse;
import ffdd.opsconsole.auth.dto.UserRegistrationRequest;
import ffdd.opsconsole.auth.infrastructure.UserOtpSendGuardRecord;
import ffdd.opsconsole.auth.mapper.UserLoginGuardMapper;
import ffdd.opsconsole.growth.application.OpsReferralRewardService;
import ffdd.opsconsole.growth.facade.DayOneInstanceFacade;
import ffdd.opsconsole.growth.domain.ReferralRewardPublicConfigView;
import ffdd.opsconsole.auth.mapper.AppUserRegistrationMapper;
import ffdd.opsconsole.auth.mapper.UserOAuthIdentityMapper;
import ffdd.opsconsole.auth.infrastructure.UserOAuthIdentityEntity;
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
import ffdd.opsconsole.shared.i18n.InterfaceLanguages;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.PessimisticLockingFailureException;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.core.env.Environment;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.util.StringUtils;

@Service
@RequiredArgsConstructor(onConstructor_ = @org.springframework.beans.factory.annotation.Autowired)
@Slf4j
public class AppUserRegistrationService {
    private final AppPaymentPasswordService paymentPasswords;

    // Compatibility constructor for existing isolated domain tests; runtime uses the full constructor.
    public AppUserRegistrationService(
            AppUserRegistrationMapper mapper,
            UserOpsMapper userMapper,
            PasswordEncoder passwordEncoder,
            UserOtpDeliveryService otpDeliveryService,
            AppUserAuthService authService,
            EventOutboxService outboxService,
            AppUserRegistrationTransactionExecutor transactionExecutor,
            Environment environment,
            OpsReferralRewardService referralRewardService,
            PlatformConfigFacade configFacade,
            UserLoginGuardMapper loginGuardMapper,
            CaptchaOtpGate captchaGate,
            DayOneInstanceFacade dayOneInstanceFacade,
            ffdd.opsconsole.content.facade.SupportRegistrationFacade supportBindingService,
            AuthNewAccountCreator accountCreator) {
        this(null,mapper,userMapper,passwordEncoder,otpDeliveryService,authService,outboxService,transactionExecutor,environment,referralRewardService,configFacade,loginGuardMapper,captchaGate,dayOneInstanceFacade,supportBindingService,accountCreator);
    }
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
    private final PlatformConfigFacade configFacade;
    private final UserLoginGuardMapper loginGuardMapper;
    private final CaptchaOtpGate captchaGate;
    private final DayOneInstanceFacade dayOneInstanceFacade;
    private final ffdd.opsconsole.content.facade.SupportRegistrationFacade supportBindingService;
    private final AuthNewAccountCreator accountCreator;

    @Transactional(propagation = Propagation.MANDATORY)
    public ApiResult<UserLoginResponse> registerProvider(String provider, String subject, String displayName,
            String sponsorCode, String clientAddress, AppLoginContext context, UserOAuthIdentityMapper identities) {
        return registerProvider(provider,subject,displayName,sponsorCode,clientAddress,context,identities,null);
    }

    /** Called only after a signed provider identity and browser receipt were verified. */
    @Transactional(propagation = Propagation.MANDATORY)
    public ApiResult<UserLoginResponse> registerProvider(String provider, String subject, String displayName,
            String sponsorCode, String clientAddress, AppLoginContext context, UserOAuthIdentityMapper identities, String captchaTicket) {
        if (UserAuthEnvironment.resolve(environment).orElse(null) != UserAuthEnvironment.PRODUCTION)
            throw new BizException(503, "OAUTH_PROFILE_FORBIDDEN");
        String ip = normalizeClientAddress(clientAddress);
        if ("unknown".equalsIgnoreCase(ip)) throw new BizException(503, "USER_REGISTRATION_CLIENT_IP_UNAVAILABLE");
        checkRegistrationLimits(ip,0,context);
        if (context.group()==AppSessionSurface.H5 && context.installationId().isEmpty()) {
            var decision=captchaGate.checkAndConsume(CaptchaScene.REGISTER,captchaTicket,ip,0,true);
            if (!decision.allowed()) throw new BizException(decision.status(),decision.code());
        }
        String code = suppliedCode(sponsorCode);
        UserEntity sponsor = resolveSponsor(code,0);
        String name = StringUtils.hasText(displayName) ? displayName.trim() : "UVEL User";
        name = name.substring(0, Math.min(name.length(), 64));
        String referralCode = nextReferralCode();
        AuthNewAccountCreator.BirthHandle birth = accountCreator.insertNew(
                new ffdd.opsconsole.user.facade.UserRegistrationWriteFacade.NewAccountCommand(
                        null, null, ip, passwordEncoder.encode(UUID.randomUUID().toString()),
                        name, referralCode, sponsor == null ? null : sponsor.getId(),
                        sponsor == null ? null : sponsor.getReferralCode(), "L1", "V0", "ACTIVE", 0, "en-US"));
        long userId = birth.customerId();
        var identity = new UserOAuthIdentityEntity();
        identity.setProvider(provider);
        identity.setExternalSubject(subject);
        identity.setUserId(userId);
        identity.setSourceEnvironment("PRODUCTION");
        identity.setDisplayName(displayName);
        try { identities.insertIdentity(identity); }
        catch (DuplicateKeyException exception) { throw new BizException(409, "OAUTH_IDENTITY_ALREADY_LINKED"); }
        var receipt = saveRegistration(userId,sponsor,0,context);
        supportBindingService.register(userId, sponsor == null ? null : sponsor.getId(), advisorCode(code), "en-US");
        dayOneInstanceFacade.provisionForRegisteredUser(userId);
        if (advisorCode(code)==null) createTeamGraphProjection(userId, 0);
        userMapper.resetLoginFailures(userId);
        accountCreator.ensureWallet(birth);
        String eventId = outboxService.publish("USER_REGISTRATION", String.valueOf(userId),
                "auth.register_completed", Map.of("userId", userId, "locale", "en"));
        accountCreator.complete(birth, eventId);
        if (sponsor != null) outboxService.publish("USER_REFERRAL", String.valueOf(userId),
                "referral.bound", Map.of("userId", userId, "sponsorUserId", sponsor.getId(),
                        "source", "nx_user.sponsor_user_id"));
        ApiResult<UserLoginResponse> session = authService.issueProviderSession(userId, clientAddress, context, null, null, null);
        if (session.getCode() != 0 || session.getData() == null) throw new BizException(session.getCode(), session.getMessage());
        return ApiResult.ok(session.getData().withRegistrationReceipt(receipt));
    }

    @PostConstruct
    void ensureSchema() {
        mapper.createTable();
    }

    @Transactional(noRollbackFor = BizException.class)
    public ApiResult<UserRegistrationOtpResponse> sendOtp(
            UserRegistrationOtpRequest request,
            String clientAddress) {
        return sendOtp(request,clientAddress,AppSessionSurface.APP,null);
    }

    @Transactional(noRollbackFor = BizException.class)
    public ApiResult<UserRegistrationOtpResponse> sendOtp(UserRegistrationOtpRequest request,String clientAddress,
            AppSessionSurface surface,String installation) {
        boolean h5=surface==AppSessionSurface.H5;
        String device=installation==null?"":installation;
        if (h5 && !device.isEmpty() && !device.matches("[A-Za-z0-9._:-]{1,128}"))
            return ApiResult.fail(422,"LOGIN_INSTALLATION_INVALID");
        boolean force=h5 && (device.isEmpty() || flag("h5_register_captcha"));
        if (request == null || !validCountryCode(request.countryCode()) || !validPhone(request.countryCode(), request.phone())) {
            return ApiResult.fail(422, "USER_REGISTRATION_PHONE_INVALID");
        }
        UserAuthEnvironment audience = UserAuthEnvironment.resolve(environment).orElse(null);
        if (audience == null) return ApiResult.fail(503, "USER_REGISTRATION_PROFILE_FORBIDDEN");
        String countryCode = normalizeCountryCode(request.countryCode());
        String phone = request.phone().trim();
        if (!otpDeliveryService.available(countryCode)) {
            return ApiResult.fail(503, "USER_OTP_DELIVERY_UNAVAILABLE");
        }
        String clientIp = normalizeClientAddress(clientAddress);
        AppOtpPolicy policy = AppOtpPolicy.load(configFacade);
        if (mapper.countRecentClient(clientIp) >= 10 || mapper.countDailyClient(clientIp) >= 100) {
            return ApiResult.fail(429, "USER_REGISTRATION_OTP_CLIENT_RATE_LIMIT");
        }
        if (mapper.countRecentPhoneInEnvironment(countryCode, phone, audience.name(), policy.cooldownSeconds()) > 0) {
            return ApiResult.fail(429, "USER_REGISTRATION_OTP_COOLDOWN");
        }
        if (mapper.countDailyPhoneInEnvironment(countryCode, phone, audience.name()) >= policy.max24h()) {
            return ApiResult.fail(429, "USER_REGISTRATION_OTP_DAILY_LIMIT");
        }
        OtpSendRateDecision rateDecision = consumeOtpSendRate(
                registrationKey(audience, countryCode, phone), LocalDateTime.now(), policy,
                request.captchaTicket(), clientIp, force);
        if (rateDecision == OtpSendRateDecision.COOLDOWN) {
            return ApiResult.fail(429, "USER_REGISTRATION_OTP_COOLDOWN");
        }
        if (rateDecision == OtpSendRateDecision.DAILY_LIMIT) {
            return ApiResult.fail(429, "USER_REGISTRATION_OTP_DAILY_LIMIT");
        }
        if (rateDecision == OtpSendRateDecision.CAPTCHA_REQUIRED) {
            return ApiResult.fail(428, "USER_CAPTCHA_REQUIRED");
        }
        if (rateDecision == OtpSendRateDecision.CAPTCHA_VERIFIER_UNAVAILABLE) {
            return ApiResult.fail(503, "USER_CAPTCHA_VERIFIER_UNAVAILABLE");
        }
        if (rateDecision == OtpSendRateDecision.CAPTCHA_TICKET_REJECTED) {
            return ApiResult.fail(428, "USER_CAPTCHA_TICKET_INVALID");
        }
        mapper.invalidateActiveInEnvironment(countryCode, phone, audience.name());
        String challengeNo = "REG-" + UUID.randomUUID().toString().replace("-", "");
        String code = otpDeliveryService.verificationCode(countryCode);
        if (mapper.insertChallengeInEnvironment(
                challengeNo, countryCode, phone, clientIp, audience.name(), code, policy.ttlMinutes()) != 1) {
            throw new IllegalStateException("USER_REGISTRATION_OTP_CREATE_FAILED");
        }
        if(h5) mapper.recordH5Challenge(challengeNo,device,force);
        try {
            UserEntity owner = findUser(countryCode, phone, audience == UserAuthEnvironment.SANDBOX ? 1 : 0);
            String language = owner == null ? request.language()
                    : ffdd.opsconsole.shared.i18n.InterfaceLanguages.baseCode(owner.getLanguage());
            otpDeliveryService.deliver(countryCode, phone, challengeNo, code, policy.ttlMinutes(), language);
        } catch (RuntimeException exception) {
            mapper.invalidateActiveInEnvironment(countryCode, phone, audience.name());
            throw new BizException(503, "USER_OTP_DELIVERY_FAILED");
        }
        return ApiResult.ok(new UserRegistrationOtpResponse(
                challengeNo,
                policy.cooldownSeconds(),
                maskPhone(phone)));
    }

    @Transactional
    public ApiResult<Map<String, Object>> verifyOtp(UserOtpLoginVerifyRequest request) {
        if (request == null || !validCountryCode(request.countryCode()) || !validPhone(request.countryCode(), request.phone())
                || !StringUtils.hasText(request.challengeNo())
                || !request.challengeNo().trim().matches("REG-[a-f0-9]{32}")
                || !StringUtils.hasText(request.code()) || !request.code().trim().matches("\\d{6}")) {
            return ApiResult.fail(422, "USER_REGISTRATION_OTP_INVALID");
        }
        UserAuthEnvironment audience = UserAuthEnvironment.resolve(environment).orElse(null);
        if (audience == null) return ApiResult.fail(503, "USER_REGISTRATION_PROFILE_FORBIDDEN");
        String countryCode = normalizeCountryCode(request.countryCode());
        String phone = request.phone().trim();
        String challengeNo = request.challengeNo().trim();
        AppOtpPolicy policy = AppOtpPolicy.load(configFacade);
        if (mapper.countValidChallengeInEnvironment(
                challengeNo, countryCode, phone, audience.name(), request.code().trim(), policy.maxVerifyAttempts()) != 1) {
            mapper.recordInvalidAttemptInEnvironment(challengeNo, countryCode, phone, audience.name(), policy.maxVerifyAttempts());
            return ApiResult.fail(422, "USER_REGISTRATION_OTP_INVALID");
        }
        // This advances the form only; register still atomically rechecks and consumes the challenge.
        return ApiResult.ok(Map.of("status", "REGISTRATION_OTP_VERIFIED"));
    }

    /**
     * Serializes per-phone registration sends before challenge invalidation and insert.  The
     * registration table has no unique open-challenge key, so the count queries alone race.
     */
    private OtpSendRateDecision consumeOtpSendRate(String key, LocalDateTime now, AppOtpPolicy policy,
            String captchaTicket, String clientAddress, boolean force) {
        loginGuardMapper.initializeOtpSendGuard(key, now);
        UserOtpSendGuardRecord guard = loginGuardMapper.lockOtpSendGuard(key);
        if (guard == null) throw new IllegalStateException("USER_OTP_SEND_GUARD_UNAVAILABLE");
        if (guard.getLastSentAt() != null && guard.getLastSentAt().plusSeconds(policy.cooldownSeconds()).isAfter(now)) {
            return OtpSendRateDecision.COOLDOWN;
        }
        int legacyCount = guard.getLegacyWindowUntil() != null && guard.getLegacyWindowUntil().isAfter(now)
                ? guard.getDaySendCount() : 0;
        int trailing24hCount = loginGuardMapper.countRecentOtpSendEvents(key, now.minusHours(24));
        if (legacyCount + trailing24hCount >= policy.max24h()) {
            return OtpSendRateDecision.DAILY_LIMIT;
        }
        CaptchaOtpGate.Decision captcha = captchaGate.checkAndConsume(
                CaptchaScene.REGISTER, captchaTicket, clientAddress, trailing24hCount,force);
        if (!captcha.allowed()) {
            return switch (captcha.code()) {
                case "USER_CAPTCHA_REQUIRED" -> OtpSendRateDecision.CAPTCHA_REQUIRED;
                case "USER_CAPTCHA_VERIFIER_UNAVAILABLE" -> OtpSendRateDecision.CAPTCHA_VERIFIER_UNAVAILABLE;
                default -> OtpSendRateDecision.CAPTCHA_TICKET_REJECTED;
            };
        }
        if (loginGuardMapper.insertOtpSendEvent(key, now) != 1) {
            throw new IllegalStateException("USER_OTP_SEND_EVENT_CREATE_FAILED");
        }
        if (loginGuardMapper.recordOtpSend(key, now, now, 0, now, trailing24hCount + 1) != 1) {
            throw new IllegalStateException("USER_OTP_SEND_GUARD_STATE_CHANGED");
        }
        return OtpSendRateDecision.ALLOWED;
    }

    private enum OtpSendRateDecision {
        ALLOWED,
        COOLDOWN,
        DAILY_LIMIT
        , CAPTCHA_REQUIRED
        , CAPTCHA_VERIFIER_UNAVAILABLE
        , CAPTCHA_TICKET_REJECTED
    }

    private String registrationKey(UserAuthEnvironment audience, String countryCode, String phone) {
        return OtpPhoneRateLimitKey.from(audience.name(), countryCode, phone);
    }

    public ApiResult<UserLoginResponse> register(
            UserRegistrationRequest request,
            String clientAddress) {
        return register(request, clientAddress, AppSessionSurface.UNKNOWN);
    }

    public ApiResult<UserLoginResponse> register(
            UserRegistrationRequest request, String clientAddress, AppSessionSurface surface) {
        return register(request, clientAddress, AppLoginContext.unavailable(surface));
    }

    public ApiResult<UserLoginResponse> register(
            UserRegistrationRequest request, String clientAddress, AppLoginContext context) {
        ApiResult<UserLoginResponse> recovered = authService.recoverCompletedLogin(context);
        if (recovered != null) return recovered;
        Integer sandbox = sandboxForNewRegistration();
        if (sandbox == null) {
            return ApiResult.fail(503, "USER_REGISTRATION_PROFILE_FORBIDDEN");
        }
        for (int attempt = 1; attempt <= 3; attempt++) {
            try {
                return transactionExecutor.execute(() -> registerInTransaction(request, clientAddress, sandbox, context));
            } catch (PessimisticLockingFailureException exception) {
                log.warn("Registration lock conflict; rolled back attempt {}/3 before retry", attempt);
            }
        }
        return ApiResult.fail(503, "USER_REGISTRATION_RETRYABLE_CONFLICT");
    }

    private ApiResult<UserLoginResponse> registerInTransaction(
            UserRegistrationRequest request,
            String clientAddress,
            int sandbox,
            AppLoginContext context) {
        if (request == null
                || !validCountryCode(request.countryCode())
                || !validPhone(request.countryCode(), request.phone())
                || !StringUtils.hasText(request.challengeNo())
                || !StringUtils.hasText(request.code())
                || !request.code().trim().matches("\\d{6}")
                || (StringUtils.hasText(request.language())
                    && !InterfaceLanguages.CODES.contains(request.language().trim().toLowerCase(Locale.ROOT)))
                || !validPassword(request.password(), request.phone())) {
            return ApiResult.fail(422, "USER_REGISTRATION_REQUEST_INVALID");
        }
        String countryCode = normalizeCountryCode(request.countryCode());
        String phone = request.phone().trim();
        String challengeNo = request.challengeNo().trim();
        String code = request.code().trim();
        UserAuthEnvironment audience = UserAuthEnvironment.resolve(environment).orElse(null);
        if (audience == null) return ApiResult.fail(503, "USER_REGISTRATION_PROFILE_FORBIDDEN");
        AppOtpPolicy policy = AppOtpPolicy.load(configFacade);
        if (mapper.consumeValidChallengeInEnvironment(
                challengeNo, countryCode, phone, audience.name(), code, policy.maxVerifyAttempts()) != 1) {
            // The consume waits for a concurrent winner. READ_COMMITTED now exposes its receipt.
            ApiResult<UserLoginResponse> recovered = authService.recoverCompletedLogin(context);
            if (recovered != null) return recovered;
            mapper.recordInvalidAttemptInEnvironment(
                    challengeNo, countryCode, phone, audience.name(), policy.maxVerifyAttempts());
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
        var challenge = mapper.h5Challenge(challengeNo);
        if (context.group()==AppSessionSurface.H5) {
            boolean force=context.installationId().isEmpty() || flag("h5_register_captcha");
            if (challenge==null && force || challenge!=null && (!context.installationId().equals(challenge.installationId())
                    || force && !challenge.captchaRequired())) throw new BizException(428,"USER_CAPTCHA_REQUIRED");
        } else if (challenge!=null) throw new BizException(422,"USER_REGISTRATION_SURFACE_MISMATCH");
        checkRegistrationLimits(registrationIp.trim(),sandbox,context);
        String invitationCode = suppliedCode(request.sponsorCode());
        UserEntity sponsor = resolveSponsor(invitationCode,sandbox);

        UserEntity user = new UserEntity();
        user.setCountryCode(countryCode);
        user.setPhone(phone);
        user.setClientIp(registrationIp.trim());
        user.setPasswordHash(passwordEncoder.encode(request.password()));
        user.setNickname("UVEL " + phone.substring(Math.max(0, phone.length() - 4)));
        user.setReferralCode(nextReferralCode());
        user.setSponsorUserId(sponsor == null ? null : sponsor.getId());
        user.setSponsorCode(sponsor == null ? null : sponsor.getReferralCode());
        user.setUserLevel("L1");
        user.setVRank("V0");
        user.setStatus("ACTIVE");
        user.setSandbox(sandbox);
        user.setLanguage(StringUtils.hasText(request.language())
                ? request.language().trim().toLowerCase(java.util.Locale.ROOT) : "en-US");
        user.setIsDeleted(0);
        AuthNewAccountCreator.BirthHandle birth;
        try {
            birth = accountCreator.insertNew(new ffdd.opsconsole.user.facade.UserRegistrationWriteFacade.NewAccountCommand(
                    user.getCountryCode(), user.getPhone(), user.getClientIp(), user.getPasswordHash(),
                    user.getNickname(), user.getReferralCode(), user.getSponsorUserId(), user.getSponsorCode(),
                    user.getUserLevel(), user.getVRank(), user.getStatus(), sandbox, user.getLanguage()));
            user.setId(birth.customerId());
        } catch (DuplicateKeyException exception) {
            throw new BizException(409, "USER_REGISTRATION_IDENTITY_CONFLICT");
        }
        if (paymentPasswords!=null) paymentPasswords.recordVerifiedPhone(user.getId(),countryCode,phone);
        var receipt = saveRegistration(user.getId(),sponsor,sandbox,context);
        supportBindingService.register(user.getId(), sponsor == null ? null : sponsor.getId(), advisorCode(invitationCode),user.getLanguage());
        dayOneInstanceFacade.provisionForRegisteredUser(user.getId());
        if (advisorCode(invitationCode)==null) createTeamGraphProjection(user.getId(), sandbox);
        userMapper.resetLoginFailures(user.getId());
        accountCreator.ensureWallet(birth);
        String registrationEventId = outboxService.publish(
                "USER_REGISTRATION",
                String.valueOf(user.getId()),
                "auth.register_completed",
                Map.of("userId", user.getId(), "locale", InterfaceLanguages.baseCode(user.getLanguage())));
        accountCreator.complete(birth, registrationEventId);
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
        ApiResult<UserLoginResponse> session = authService.issueRegisteredSession(user, clientAddress, context);
        if (session.getData()==null || session.getCode()!=0) throw new BizException(session.getCode(),session.getMessage());
        return ApiResult.ok(session.getData().withRegistrationReceipt(receipt));
    }

    private String suppliedCode(String raw) {
        String code=canonicalReferralCode(raw);
        if(code.isEmpty() && StringUtils.hasText(raw)) throw new BizException(422,"USER_REGISTRATION_SPONSOR_INVALID");
        return code;
    }

    private static String advisorCode(String code) {
        return code.startsWith("CS")?code:null;
    }

    private UserEntity resolveSponsor(String code,int sandbox) {
        if (code.isEmpty()) return null;
        if (advisorCode(code)!=null) {
            if (sandbox!=0 || !code.matches("CS[A-HJ-NP-Z2-9]{6}")) throw new BizException(422,"USER_REGISTRATION_SPONSOR_NOT_FOUND");
            return null;
        }
        if (!code.matches("[A-Z0-9]{4,32}")) throw new BizException(422,"USER_REGISTRATION_SPONSOR_INVALID");
        List<UserEntity> candidates=mapper.findActiveSponsorsByCanonicalCode(code);
        if (candidates.isEmpty()) throw new BizException(422,"USER_REGISTRATION_SPONSOR_NOT_FOUND");
        if (candidates.size()!=1) throw new BizException(409,"USER_REGISTRATION_SPONSOR_AMBIGUOUS");
        String stored=candidates.get(0).getReferralCode();
        if (!code.equals(canonicalReferralCode(stored))) throw new BizException(409,"USER_REGISTRATION_SPONSOR_STATE_CHANGED");
        UserEntity sponsor=mapper.findSponsorForUpdate(stored);
        if (sponsor==null) throw new BizException(409,"USER_REGISTRATION_SPONSOR_STATE_CHANGED");
        if (sandboxOf(sponsor)!=sandbox) throw new BizException(409,"USER_REGISTRATION_SPONSOR_ENVIRONMENT_MISMATCH");
        return sponsor;
    }

    private void checkRegistrationLimits(String ip,int sandbox,AppLoginContext context) {
        boolean h5=context.group()==AppSessionSurface.H5;
        if (sandbox==1 && !h5) return;
        // This existing K1 row lock spans the count, account creation and origin insert on both registration paths.
        int ipLimit=requiredK1MaxSignupPerIp24h();
        if (mapper.registeredByIp(ip,sandbox)>=ipLimit) throw new BizException(409,"USER_REGISTRATION_K1_IP_LIMIT");
        if (h5 && !context.installationId().isEmpty()) {
            int limit=Integer.parseInt(configFacade.activeValue("auth.risk.h5_register_device_limit").orElse("3"));
            if(limit>0 && mapper.registeredByDevice(context.installationId(),sandbox)>=limit)
                throw new BizException(409,"USER_REGISTRATION_DEVICE_LIMIT");
        }
    }

    private boolean flag(String key) {
        return !"0".equals(configFacade.activeValue("auth.risk."+key).orElse("1"));
    }

    private UserLoginResponse.RegistrationReceipt saveRegistration(long id,UserEntity sponsor,int sandbox,AppLoginContext context) {
        boolean freeze=context.group()==AppSessionSurface.H5 && flag("h5_freeze_newcomer_gift");
        var receipt=registrationReceipt(sponsor,sandbox,freeze);
        mapper.recordOrigin(id,context.loginAttemptId(),context.group().name(),context.installationId(),sandbox,freeze,receipt);
        return receipt;
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

    private UserLoginResponse.RegistrationReceipt registrationReceipt(UserEntity sponsor, int sandbox,boolean freeze) {
        String sourceEnvironment = sandbox == 1 ? "SANDBOX" : "PRODUCTION";
        BigDecimal giftUsdt = null;
        BigDecimal giftNex = null;
        String status = "UNAVAILABLE";
        if (referralRewardService != null) {
            try {
                ReferralRewardPublicConfigView config = referralRewardService.publicConfig();
                if (config != null && config.enabled() && config.effectiveAt()!=null && !config.effectiveAt().isAfter(java.time.Instant.now())
                        && config.welcomeGift() != null
                        && config.welcomeGift().usdtAmount() != null
                        && config.welcomeGift().nexAmount() != null) {
                    giftUsdt = config.welcomeGift().usdtAmount();
                    giftNex = config.welcomeGift().nexAmount();
                    status = freeze ? "FROZEN" : "PENDING_REVIEW";
                }
            } catch (RuntimeException ignored) {
                // Registration remains committed; the UI must fail closed when the
                // optional display projection is unavailable.
            }
        }
        return new UserLoginResponse.RegistrationReceipt(
                sponsor==null?null:canonicalReferralCode(sponsor.getReferralCode()),
                sponsor==null?null:maskSponsorName(sponsor.getNickname()), sourceEnvironment, status, giftUsdt, giftNex);
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
