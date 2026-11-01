package ffdd.opsconsole.device.application;

import ffdd.opsconsole.common.boundary.PhoneComputeEngineContract;
import ffdd.opsconsole.common.boundary.PhoneExecutionSessionContract;
import ffdd.opsconsole.device.application.TestCloudExecutorService.Identity;
import ffdd.opsconsole.onboarding.mapper.PhoneComputeEligibilityMapper;
import ffdd.opsconsole.shared.exception.BizException;
import ffdd.opsconsole.shared.security.CurrentAppSessionService;
import java.time.*;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.core.env.Environment;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Service;

/** Current native session owns its own phone; a readiness report grants no payment authority. */
@Service
@ConditionalOnProperty(name="nexion.compute-v2.enabled",havingValue="true")
public class PhoneComputeV2Access implements PhoneComputeEngineContract {
    public static final String RUNTIME="phone-minilm-wasm-v1";
    public static final String MODEL="all-MiniLM-L6-v2";
    public static final String REVISION="1110a243fdf4706b3f48f1d95db1a4f5529b4d41";
    public static final String MODEL_SHA="6fd5d72fe4589f189f8ebc006442dbb529bb7ce38f8082112682524616046452";
    public record EngineReady(String runtimeKey,String modelRevision,String modelDigest,boolean engineReady,String modelSha256,String enginePlatform) { }
    private record Engine(long userId,String installationId,String digest,Instant observedAt) { }
    private final PhoneComputeEligibilityMapper mapper;
    private final CurrentAppSessionService sessions;
    private final PhoneExecutionSessionContract ownership;
    private final Clock clock;
    private final Set<Long> enrolled;
    private final Map<Long,Engine> engines=new ConcurrentHashMap<>();

    public PhoneComputeV2Access(PhoneComputeEligibilityMapper mapper,CurrentAppSessionService sessions,
            PhoneExecutionSessionContract ownership,Clock clock,Environment environment) {
        this.mapper=mapper;this.sessions=sessions;this.ownership=ownership;this.clock=clock;
        var ids=new HashSet<Long>();
        for(String value:environment.getProperty("nexion.compute-v2.phone-device-ids","").split(","))if(!value.isBlank()) {
            if(!value.trim().matches("[1-9][0-9]{0,18}") || !ids.add(Long.parseLong(value.trim())))throw new IllegalArgumentException("COMPUTE_V2_PHONE_IDS_INVALID");
        }
        enrolled=Set.copyOf(ids);
    }

    boolean manages(long deviceId){return enrolled.contains(deviceId);}
    Set<Long> devices(){return enrolled;}
    static boolean isPhone(Identity identity){return identity.executorId().startsWith("phone-v2:");}

    /** Caller owns a short SQL transaction, including the canonical account/session lock. */
    Identity current(Authentication auth) {
        var session=sessions.requireSession(auth);
        if(!"APP".equals(session.getSessionGroup()) || !Boolean.TRUE.equals(session.getExecutionSupported())
                || session.getInstallationId()==null)throw new BizException(403,"PHONE_EXECUTION_UNSUPPORTED");
        var rows=mapper.phones(session.getUserId()).stream().filter(phone->session.getInstallationId().equals(phone.installationId())).toList();
        if(rows.size()!=1 || !enrolled.contains(rows.get(0).deviceId()))throw new BizException(403,"COMPUTE_V2_PHONE_NOT_ENROLLED");
        var phone=rows.get(0);ownership.requireExecutionOwner(session.getUserId(),phone.deviceId());
        if(!session.getInstallationId().equals(phone.executionInstallationId()))throw new BizException(409,"PHONE_EXECUTION_SESSION_REQUIRED");
        return new Identity("phone-v2:"+phone.deviceId()+":"+TestCloudExecutorService.sha256(session.getInstallationId()),
                session.getUserId(),phone.deviceId(),phone.instanceNo(),"",MODEL,"");
    }

    void requireCurrent(Authentication auth,Identity expected) {
        if(!current(auth).equals(expected))throw new BizException(403,"COMPUTE_V2_PHONE_OWNER_CHANGED");
    }

    void eligible(Identity identity,boolean completing) {
        if(!manages(identity.deviceId()))throw new BizException(403,"COMPUTE_V2_PHONE_NOT_ENROLLED");
        ownership.requireExecutionOwner(identity.ownerId(),identity.deviceId());
        var phone=mapper.phones(identity.ownerId()).stream().filter(row->row.deviceId()==identity.deviceId()).findFirst()
                .orElseThrow(()->new BizException(403,"COMPUTE_V2_PHONE_OWNER_CHANGED"));
        if(!identity.instanceNo().equals(phone.instanceNo()) || phone.installationId()==null
                || !phone.installationId().equals(phone.executionInstallationId()) || !phone.calibrated())
            throw new BizException(409,"PHONE_CALIBRATION_OR_BINDING_REQUIRED");
        if(phone.activatedAt()==null || phone.deactivatedAt()!=null || !completing && phone.pendingDeactivate()
                || !Set.of("ACTIVE","ONLINE","BUSY","RUNNING").contains(phone.status().toUpperCase(Locale.ROOT)))
            throw new BizException(409,"PHONE_DEVICE_INACTIVE");
        LocalDateTime now=LocalDateTime.now(clock);
        if(phone.heartbeatAt()==null || phone.heartbeatAt().isAfter(now) || phone.heartbeatAt().isBefore(now.minusSeconds(120)))
            throw new BizException(409,"PHONE_TELEMETRY_STALE");
        if(!Boolean.TRUE.equals(phone.networkReachable()))throw new BizException(409,"PHONE_OFFLINE");
        if(phone.batteryLevel()==null || phone.batteryLevel()<20)throw new BizException(409,"PHONE_LOW_BATTERY");
        if(phone.pausedReason()!=null && !phone.pausedReason().isBlank())throw new BizException(409,"PHONE_DEVICE_PAUSED");
        if(!ready(identity.ownerId(),identity.deviceId(),phone.installationId()))throw new BizException(409,"PHONE_EXECUTOR_UNAVAILABLE");
    }

    void loaded(Identity identity,EngineReady report,String installedDigest) {
        if(report==null || !report.engineReady() || !RUNTIME.equals(report.runtimeKey()) || !REVISION.equals(report.modelRevision())
                || !MODEL_SHA.equals(report.modelSha256()) || !"app-plus".equals(report.enginePlatform())
                || !installedDigest.equals(report.modelDigest()))throw new BizException(422,"PHONE_ENGINE_BINDING_INVALID");
        var phone=mapper.phones(identity.ownerId()).stream().filter(row->row.deviceId()==identity.deviceId()).findFirst().orElseThrow();
        engines.put(identity.deviceId(),new Engine(identity.ownerId(),phone.installationId(),installedDigest,clock.instant()));
    }

    void heartbeat(Identity identity) {
        engines.computeIfPresent(identity.deviceId(),(id,engine)->engine.userId()==identity.ownerId()
                ?new Engine(engine.userId(),engine.installationId(),engine.digest(),clock.instant()):engine);
    }
    void exited(long deviceId){engines.remove(deviceId);}

    @Override public boolean ready(long userId,long deviceId,String installationId) {
        Engine engine=engines.get(deviceId);
        return enrolled.contains(deviceId) && engine!=null && engine.userId()==userId && engine.installationId().equals(installationId)
                && !engine.observedAt().isBefore(clock.instant().minusSeconds(120));
    }
}
