package ffdd.opsconsole.onboarding.application;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import ffdd.opsconsole.common.boundary.ApplicationService;
import ffdd.opsconsole.onboarding.mapper.OnboardingCalibrationMapper;
import ffdd.opsconsole.platform.facade.PlatformConfigFacade;
import ffdd.opsconsole.shared.audit.AuditLogService;
import ffdd.opsconsole.shared.audit.AuditLogWriteRequest;
import ffdd.opsconsole.shared.exception.BizException;
import ffdd.opsconsole.shared.security.PhoneReplacementAdminAccess;
import java.time.Clock;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.transaction.annotation.Transactional;

/** E6 phone model risk settings, saved directly (no A2) and audited, with the last week's findings. */
@ApplicationService
@RequiredArgsConstructor
public class PhoneModelRiskAdminService {
    public static final int STATS_DAYS = 7;
    private static final ObjectMapper JSON = new ObjectMapper();

    private final PlatformConfigFacade config;
    private final OnboardingCalibrationMapper mapper;
    private final PhoneReplacementAdminAccess access;
    private final AuditLogService audit;
    private final Clock clock;

    public record Update(Long expectedRevision, PhoneModelRisk.Mode installModelLock, PhoneModelRisk.Mode osVersion,
                         PhoneModelRisk.Mode screenScale, PhoneModelRisk.Mode screenSize, PhoneModelRisk.Mode missingSignals,
                         PhoneModelRisk.Mode accountModelChurn, Integer churnWindowDays, Integer churnModelCount,
                         String reason, Boolean confirmed) { }

    @Transactional(readOnly = true)
    public Map<String, Object> read() {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("settings", new PhoneModelRiskConfig(config, clock).read());
        data.put("stats", stats());
        return data;
    }

    @Transactional(rollbackFor = Exception.class)
    public Map<String, Object> update(Update input, String key) {
        long actor = access.require("device_e6_write");
        if (input == null || input.expectedRevision() == null || !Boolean.TRUE.equals(input.confirmed()))
            throw new BizException(422, "PHONE_RISK_SETTINGS_CONFIRMATION_REQUIRED");
        // Every setting is sent: a missing one would silently fall back to its default.
        if (java.util.stream.Stream.of(input.installModelLock(), input.osVersion(), input.screenScale(), input.screenSize(),
                input.missingSignals(), input.accountModelChurn(), input.churnWindowDays(), input.churnModelCount())
                .anyMatch(java.util.Objects::isNull)) throw new BizException(422, "PHONE_RISK_SETTINGS_INVALID");
        if (input.reason() == null || input.reason().trim().length() < 8 || input.reason().trim().length() > 200)
            throw new BizException(422, "PHONE_RISK_SETTINGS_REASON_INVALID");
        var store = new PhoneModelRiskConfig(config, clock);
        var before = store.read();
        var after = store.save(new PhoneModelRisk.Settings(0, input.installModelLock(), input.osVersion(), input.screenScale(),
                input.screenSize(), input.missingSignals(), input.accountModelChurn(), input.churnWindowDays(),
                input.churnModelCount(), null, null, null), input.expectedRevision(), actor, input.reason());
        Map<String, Object> detail = new LinkedHashMap<>();
        detail.put("before", JSON.convertValue(before, new TypeReference<Map<String, Object>>() { }));
        detail.put("after", JSON.convertValue(after, new TypeReference<Map<String, Object>>() { }));
        detail.put("reason", after.reason());
        audit.recordRequired(AuditLogWriteRequest.builder()
                .traceId(key == null || key.isBlank() ? "PRS" + UUID.randomUUID().toString().replace("-", "") : key.trim())
                .actorType("ADMIN").actorId(actor).action("ADMIN.PHONE_RISK_SETTINGS_UPDATED").resourceType("CONFIG")
                .resourceId(PhoneModelRiskConfig.KEY).result("SUCCEEDED").riskLevel("HIGH").detail(detail).build());
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("settings", after);
        data.put("stats", stats());
        return data;
    }

    /** Per check: calibrations flagged and held and accounts involved, and the most flagged models. */
    private Map<String, Object> stats() {
        Map<String, Object> stats = new LinkedHashMap<>();
        stats.put("days", STATS_DAYS);
        stats.put("checks", mapper.riskCheckStats(STATS_DAYS));
        stats.put("models", mapper.riskModelStats(STATS_DAYS));
        return stats;
    }
}
