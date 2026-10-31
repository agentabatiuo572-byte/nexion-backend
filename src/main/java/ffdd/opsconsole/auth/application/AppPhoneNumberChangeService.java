package ffdd.opsconsole.auth.application;

import ffdd.opsconsole.auth.mapper.AppPhoneNumberChangeMapper;
import ffdd.opsconsole.platform.facade.PlatformConfigFacade;
import ffdd.opsconsole.shared.audit.AuditLogService;
import ffdd.opsconsole.shared.audit.AuditLogWriteRequest;
import ffdd.opsconsole.shared.exception.BizException;
import ffdd.opsconsole.shared.security.SupportedUserPhonePolicy;
import ffdd.opsconsole.shared.security.UserSessionRevocationService;
import ffdd.opsconsole.user.infrastructure.UserEntity;
import ffdd.opsconsole.user.mapper.UserOpsMapper;
import jakarta.annotation.PostConstruct;
import java.time.LocalDateTime;
import java.util.Map;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

/** Changing the account's login number; unrelated to changing its bound handset. */
@Service
@RequiredArgsConstructor(onConstructor_ = @org.springframework.beans.factory.annotation.Autowired)
public class AppPhoneNumberChangeService {
    private final AppPaymentPasswordService paymentPasswords;

    // Compatibility constructor for existing isolated domain tests; runtime uses the full constructor.
    public AppPhoneNumberChangeService(
            AppPhoneNumberChangeMapper mapper,
            AppUserSecurityService securityService,
            AppUserSecurityVerificationGuard verificationGuard,
            UserOpsMapper users,
            UserOtpDeliveryService delivery,
            PlatformConfigFacade config,
            UserSessionRevocationService revocations,
            AuditLogService audit) {
        this(null,mapper,securityService,verificationGuard,users,delivery,config,revocations,audit);
    }
    private final AppPhoneNumberChangeMapper mapper;
    private final AppUserSecurityService securityService;
    private final AppUserSecurityVerificationGuard verificationGuard;
    private final UserOpsMapper users;
    private final UserOtpDeliveryService delivery;
    private final PlatformConfigFacade config;
    private final UserSessionRevocationService revocations;
    private final AuditLogService audit;

    @PostConstruct
    void ensureTable() {
        mapper.createTable();
    }

    @Scheduled(fixedDelayString = "${nexion.auth.phone-change.scrub-delay-ms:3600000}")
    public void scrubExpiredChallenges() {
        mapper.scrubExpired();
    }

    @Transactional(readOnly = true)
    public Map<String, Object> policy(long userId) {
        Policy policy = currentPolicy();
        int used = mapper.countCompleted(userId, policy.windowDays());
        LocalDateTime threshold = used >= policy.maxChanges()
                ? mapper.thresholdCompleted(userId, policy.windowDays(), policy.maxChanges() - 1) : null;
        return Map.of(
                "windowDays", policy.windowDays(),
                "maxChanges", policy.maxChanges(),
                "usedChanges", used,
                "remainingChanges", Math.max(0, policy.maxChanges() - used),
                "nextAvailableAt", threshold == null ? ""
                        : threshold.plusDays(policy.windowDays()).toString());
    }

    @Transactional(noRollbackFor = AppUserSecurityService.PreWriteRejection.class)
    public Map<String, Object> start(long userId, String currentPassword, String countryCode, String phone) {
        if (!StringUtils.hasText(currentPassword)) throw new BizException(422, "CURRENT_PASSWORD_REQUIRED");
        String country = normalizeCountry(countryCode);
        String nextPhone = phone == null ? "" : phone.trim();
        String nextIdentity;
        try {
            nextIdentity = SupportedUserPhonePolicy.toE164Digits(country, nextPhone);
        } catch (IllegalArgumentException ex) {
            throw new BizException(422, "USER_PHONE_INVALID");
        }
        securityService.verifyCurrentPassword(userId, currentPassword, "PHONE_NUMBER_CHANGE");
        UserEntity user = users.selectById(userId);
        if (user == null || !"ACTIVE".equals(user.getStatus()) || !StringUtils.hasText(user.getPhone())
                || !SupportedUserPhonePolicy.isSupportedDestination(user.getCountryCode(), user.getPhone())) {
            throw new BizException(409, "USER_PHONE_CHANGE_UNAVAILABLE");
        }
        if (nextIdentity.equals(SupportedUserPhonePolicy.toE164Digits(user.getCountryCode(), user.getPhone()))) {
            throw new BizException(422, "USER_PHONE_UNCHANGED");
        }
        if (mapper.targetOwners(userId, country, nextPhone, alternate(country, nextPhone)) > 0) {
            throw new BizException(409, "USER_PHONE_ALREADY_IN_USE");
        }
        enforceLimit(userId);
        Map<String, Object> existing = mapper.latestOpen(userId);
        if (existing != null
                && user.getCountryCode().equals(existing.get("oldCountryCode"))
                && user.getPhone().equals(existing.get("oldPhone"))
                && country.equals(existing.get("newCountryCode"))
                && nextPhone.equals(existing.get("newPhone"))) {
            return challengeResponse(String.valueOf(existing.get("changeNo")), user.getPhone(), nextPhone,
                    ((Number) existing.get("expiresInSeconds")).intValue());
        }
        if (mapper.recentStarts(userId) > 0 || mapper.dailyStarts(userId) >= 5) {
            throw new BizException(429, "USER_PHONE_CHANGE_CHALLENGE_RATE_LIMITED");
        }
        if (!delivery.available(user.getCountryCode()) || !delivery.available(country)) {
            throw new BizException(503, "USER_OTP_DELIVERY_UNAVAILABLE");
        }
        String oldCode = delivery.verificationCode(user.getCountryCode());
        String newCode = delivery.verificationCode(country);
        String changeNo = "PHONE-" + UUID.randomUUID().toString().replace("-", "");
        mapper.expirePending(userId);
        if (mapper.insertChallenge(changeNo, userId, user.getCountryCode(), user.getPhone(),
                country, nextPhone, oldCode, newCode) != 1) {
            throw new IllegalStateException("USER_PHONE_CHANGE_CHALLENGE_NOT_CREATED");
        }
        delivery.deliver(user.getCountryCode(), user.getPhone(), changeNo + "-OLD", oldCode, 10);
        delivery.deliver(country, nextPhone, changeNo + "-NEW", newCode, 10);
        record(userId, "USER_PHONE_NUMBER_CHANGE_CHALLENGE_SENT", changeNo, Map.of("delivery", "OLD_AND_NEW"));
        return challengeResponse(changeNo, user.getPhone(), nextPhone, 600);
    }

    @Transactional(noRollbackFor = AppUserSecurityService.PreWriteRejection.class)
    public Map<String, Object> confirm(long userId, String sessionId, String changeNo,
            String oldCode, String newCode) {
        if (!StringUtils.hasText(changeNo) || !changeNo.matches("PHONE-[a-f0-9]{32}")
                || !validCode(oldCode) || !validCode(newCode)) {
            throw new BizException(422, "USER_PHONE_CHANGE_CODES_REQUIRED");
        }
        verificationGuard.lock(userId);
        UserEntity user = users.selectById(userId);
        if (user == null || !"ACTIVE".equals(user.getStatus())) throw new BizException(401, "USER_AUTH_REQUIRED");
        Map<String, Object> row = mapper.lock(userId, changeNo);
        if (row == null) throw new BizException(404, "USER_PHONE_CHANGE_NOT_FOUND");
        if (row.get("completedAt") != null) return Map.of("changed", true);
        if (mapper.open(userId, changeNo) != 1) {
            throw new BizException(409, "USER_PHONE_CHANGE_EXPIRED");
        }
        if (!String.valueOf(row.get("oldCountryCode")).equals(user.getCountryCode())
                || !String.valueOf(row.get("oldPhone")).equals(user.getPhone())) {
            throw new BizException(409, "USER_PHONE_CHANGE_ACCOUNT_CHANGED");
        }
        enforceLimit(userId);
        if (mapper.codesMatch(userId, changeNo, oldCode, newCode) != 1) {
            mapper.recordFailedAttempt(userId, changeNo);
            throw new AppUserSecurityService.PreWriteRejection(401, "USER_PHONE_CHANGE_CODE_INVALID");
        }
        String country = String.valueOf(row.get("newCountryCode"));
        String phone = String.valueOf(row.get("newPhone"));
        if (mapper.targetOwners(userId, country, phone, alternate(country, phone)) > 0) {
            throw new BizException(409, "USER_PHONE_ALREADY_IN_USE");
        }
        try {
            if (mapper.changeUserPhone(userId, user.getCountryCode(), user.getPhone(), country, phone) != 1) {
                throw new BizException(409, "USER_PHONE_CHANGE_ACCOUNT_CHANGED");
            }
        } catch (DuplicateKeyException ex) {
            throw new BizException(409, "USER_PHONE_ALREADY_IN_USE");
        }
        if (mapper.complete(userId, changeNo) != 1) {
            throw new IllegalStateException("USER_PHONE_CHANGE_RECEIPT_FAILED");
        }
        if (paymentPasswords!=null) paymentPasswords.transferVerifiedPhone(userId,country,phone);
        int revoked = revocations.revokeOthers(userId, sessionId);
        record(userId, "USER_PHONE_NUMBER_CHANGED", changeNo, Map.of("revokedOtherSessions", revoked));
        return Map.of("changed", true);
    }

    private void enforceLimit(long userId) {
        Policy policy = currentPolicy();
        if (mapper.countCompleted(userId, policy.windowDays()) >= policy.maxChanges()) {
            throw new BizException(409, "USER_PHONE_CHANGE_LIMIT_REACHED");
        }
    }

    private Policy currentPolicy() {
        return new Policy(configInt("auth.phone_change.window_days", 365, 1, 3650),
                configInt("auth.phone_change.max_count", 5, 1, 20));
    }

    private int configInt(String key, int fallback, int min, int max) {
        try {
            int value = Integer.parseInt(config.activeValue(key).orElse("").trim());
            return value >= min && value <= max ? value : fallback;
        } catch (NumberFormatException ex) {
            return fallback;
        }
    }

    private String normalizeCountry(String value) {
        String normalized = value == null ? "" : value.trim().replace("+", "");
        return normalized;
    }

    private String alternate(String country, String phone) {
        if (!"84".equals(country)) return phone;
        return phone.startsWith("0") ? phone.substring(1) : "0" + phone;
    }

    private boolean validCode(String code) {
        return code != null && code.matches("[0-9]{6}");
    }

    private String mask(String phone) {
        return "****" + phone.substring(Math.max(0, phone.length() - 4));
    }

    private Map<String, Object> challengeResponse(String changeNo, String oldPhone, String newPhone, int expiresInSeconds) {
        return Map.of("changeNo", changeNo, "expiresInSeconds", expiresInSeconds,
                "oldPhoneMasked", mask(oldPhone), "newPhoneMasked", mask(newPhone));
    }

    private void record(long userId, String action, String changeNo, Object detail) {
        audit.recordRequired(AuditLogWriteRequest.builder()
                .action(action).resourceType("USER_SECURITY").resourceId(changeNo)
                .userId(userId).actorId(userId).actorType("USER").actorUsername("user:" + userId)
                .result("SUCCESS").riskLevel("HIGH").detail(detail).build());
    }

    private record Policy(int windowDays, int maxChanges) {
    }
}
