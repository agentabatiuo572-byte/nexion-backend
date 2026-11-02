package ffdd.opsconsole.onboarding.application;

import ffdd.opsconsole.onboarding.mapper.PhoneComputeEligibilityMapper;
import ffdd.opsconsole.shared.security.CurrentAppSessionService;
import java.time.Clock;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class PhoneComputeEligibilityService {
    private final PhoneComputeEligibilityMapper mapper;
    private final CurrentAppSessionService sessions;
    private final Optional<ffdd.opsconsole.common.boundary.PhoneComputeEngineContract> engines;
    private final Clock clock;

    public record PhoneEligibility(long deviceId,String instanceNo,boolean eligible,List<String> blockers,
            LocalDateTime heartbeatAt,Integer batteryLevel,Boolean networkReachable) { }
    public record Eligibility(LocalDateTime serverNow,String executionLocation,boolean executorSupported,
            List<String> supportedTaskClasses,List<PhoneEligibility> devices,String source,String sourceEnvironment,
            String runId,boolean serverCanonical) { }

    @Transactional
    public Eligibility read(long userId) {
        var now=LocalDateTime.now(clock);
        var appSessions=sessions.adminSummary(userId);
        var devices=mapper.phones(userId).stream().map(phone -> {
            var blockers=new ArrayList<String>();
            if (phone.activatedAt()==null || phone.deactivatedAt()!=null || phone.pendingDeactivate()
                    || !List.of("ACTIVE","ONLINE").contains(phone.status().toUpperCase(Locale.ROOT)))
                blockers.add("PHONE_DEVICE_INACTIVE");
            if (phone.installationId()==null) blockers.add("PHONE_BINDING_REQUIRED");
            if (!phone.calibrated()) blockers.add("PHONE_CALIBRATION_REQUIRED");
            if (phone.installationId()==null || !phone.installationId().equals(phone.executionInstallationId())
                    || appSessions.stream().filter(session -> "APP".equals(session.sessionGroup())
                    && session.executionSupported() && phone.installationId().equals(session.installationId()))
                    .mapToLong(session -> session.sessionCount()).sum()!=1)
                blockers.add("PHONE_EXECUTION_SESSION_REQUIRED");
            if (phone.heartbeatAt()==null || phone.heartbeatAt().isAfter(now)
                    || phone.heartbeatAt().isBefore(now.minusSeconds(120))) blockers.add("PHONE_TELEMETRY_STALE");
            if (!Boolean.TRUE.equals(phone.networkReachable())) blockers.add("PHONE_OFFLINE");
            if (phone.batteryLevel()==null || phone.batteryLevel()<20) blockers.add("PHONE_LOW_BATTERY");
            if (phone.pausedReason()!=null && !phone.pausedReason().isBlank()
                    && !List.of("PHONE_LOW_BATTERY","PHONE_OFFLINE").contains(phone.pausedReason()))
                blockers.add("PHONE_DEVICE_PAUSED");
            boolean engineReady=phone.installationId()!=null && engines.map(engine ->
                    engine.ready(userId,phone.deviceId(),phone.installationId())).orElse(false);
            if (!engineReady) blockers.add("PHONE_EXECUTOR_UNAVAILABLE");
            return new PhoneEligibility(phone.deviceId(),phone.instanceNo(),blockers.isEmpty(),List.copyOf(blockers),
                    phone.heartbeatAt(),phone.batteryLevel(),phone.networkReachable());
        }).toList();
        boolean supported=devices.stream().anyMatch(phone -> !phone.blockers().contains("PHONE_EXECUTOR_UNAVAILABLE"));
        return new Eligibility(now,"LOCAL_PHONE",supported,supported?List.of("EM"):List.of(),devices,"server","PRODUCTION","",true);
    }
}
