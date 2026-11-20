package ffdd.opsconsole.device.application;

import com.fasterxml.jackson.databind.ObjectMapper;
import ffdd.opsconsole.device.compute.v2.ComputeV2Runtime;
import ffdd.opsconsole.device.mapper.TestCloudRequestMapper;
import ffdd.opsconsole.shared.exception.BizException;
import java.nio.file.*;
import java.time.Clock;
import java.util.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataAccessException;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/** TEST capacity is reserved before activation; existing executors never depend on this snapshot. */
@Component
public class TestCloudActivationAdmission {
    private final TestCloudRequestMapper mapper;
    private final Environment environment;
    private final Clock clock;
    private final Optional<ComputeV2Runtime> computeV2Runtime;
    private final Set<Long> enrolledDevices;
    private static final ObjectMapper JSON=new ObjectMapper();
    public TestCloudActivationAdmission(TestCloudRequestMapper mapper,Environment environment,Clock clock) {
        this(mapper,environment,clock,Optional.empty());
    }
    @Autowired
    public TestCloudActivationAdmission(TestCloudRequestMapper mapper,Environment environment,Clock clock,Optional<ComputeV2Runtime> computeV2Runtime) {
        this.mapper=mapper;this.environment=environment;this.clock=clock;this.computeV2Runtime=computeV2Runtime;
        var devices=new LinkedHashSet<Long>();
        for(String text:(v2Enabled()?environment.getProperty("nexion.compute-v2.device-ids",""):"").split(",")) if(!text.isBlank()) {
            long device=Long.parseLong(text.trim());
            if(device<=0 || !devices.add(device) || devices.size()>6000) throw new IllegalArgumentException("COMPUTE_V2_DEVICE_IDS_INVALID");
        }
        enrolledDevices=Collections.unmodifiableSet(devices);
    }
    private boolean legacyEnabled() {
        return "true".equals(environment.getProperty(TestCloudExecutorService.PREFIX+"enabled"))
                && "TEST".equals(environment.getProperty(TestCloudExecutorService.PREFIX+"deployment-scope"))
                && Set.of(environment.getActiveProfiles()).equals(Set.of("dev"));
    }
    String legacyTaskConfig() {
        return legacyEnabled() ? environment.getProperty(TestCloudExecutorService.PREFIX + "task-config-id", "") : "";
    }
    private boolean v2Enabled() {
        return "true".equals(environment.getProperty("nexion.compute-v2.enabled"))
                && "TEST".equals(environment.getProperty("nexion.compute-v2.deployment-scope"))
                && Set.of(environment.getActiveProfiles()).equals(Set.of("dev"));
    }
    private boolean enabled() { return legacyEnabled() || v2Enabled(); }
    private Set<Long> logicalDevices() { return v2Enabled() ? enrolledDevices : Set.of(); }
    private int activeV1Resources() {
        return logicalDevices().isEmpty() ? mapper.activeResourceExecutors() : mapper.activeV1ResourceExecutors(logicalDevices());
    }
    private int v2PhysicalResources() {
        if(!v2Enabled() || computeV2Runtime.isEmpty()) return 0;
        if(!computeV2Runtime.get().isReady()) throw unavailable();
        return computeV2Runtime.get().activeExecutionCount();
    }
    public record HostSnapshot(long observedAtEpochSeconds,long expiresAtEpochSeconds,boolean allowNewActivations,
            int maxExecutors,boolean draining,long physicalMemoryBytes,long availableMemoryBytes,long reserveBytes,
            long committedBytes,long outstandingBuildBytes) { }
    public HostSnapshot hostSnapshot() { return readSnapshot(Path.of("/run/uvel-task-control/capacity.json"),true); }
    HostSnapshot readSnapshot(Path snapshot,boolean requireMemoryBudget) {
        try(var input=Files.newInputStream(snapshot)) {
            byte[] bytes=input.readNBytes(4097);
            if(bytes.length>4096) throw new IllegalArgumentException();
            var value=JSON.readTree(bytes); if(value==null || !value.isObject()) throw new IllegalArgumentException();
            long now=clock.instant().getEpochSecond();
            long observed=value.path("observedAtEpochSeconds").asLong(-1),expires=value.path("expiresAtEpochSeconds").asLong(-1);
            int maximum=value.path("maxActiveExecutors").asInt(-1);
            if(value.path("version").asInt()!=1 || !value.path("allowNewActivations").isBoolean()
                    || observed>now || observed<now-30 || expires<=now || expires>observed+30 || maximum<1
                    || value.has("draining")&&!value.path("draining").isBoolean()) throw new IllegalArgumentException();
            long physical=value.path("physicalMemoryBytes").asLong(-1),available=value.path("availableMemoryBytes").asLong(-1),
                    reserve=value.path("reserveBytes").asLong(-1),committed=value.path("committedBytes").asLong(-1),builds=value.path("outstandingBuildBytes").asLong(-1);
            if(requireMemoryBudget && (!value.path("draining").isBoolean() || physical<=0 || available<0 || available>physical
                    || reserve!=(physical+4)/5 || committed<0 || builds<0 || available-builds<reserve || committed>physical-reserve))
                throw new IllegalArgumentException();
            return new HostSnapshot(observed,expires,value.path("allowNewActivations").booleanValue(),maximum,
                    value.path("draining").asBoolean(false),physical,available,reserve,committed,builds);
        } catch(java.io.IOException | IllegalArgumentException unavailable) { throw unavailable(); }
    }
    public boolean canStartV2Execution(int occupiedV2Permits) {
        return canStartV2Execution(Path.of("/run/uvel-task-control/capacity.json"),occupiedV2Permits);
    }
    boolean canStartV2Execution(Path snapshot,int occupiedV2Permits) {
        if(!v2Enabled() || occupiedV2Permits<0 || occupiedV2Permits>6) return false;
        try {
            HostSnapshot host=readSnapshot(snapshot,true);
            if(!host.allowNewActivations() || host.draining() || computeV2Runtime.isPresent()&&!computeV2Runtime.get().isReady()) return false;
            return (long)mapper.actualV1Executions()+occupiedV2Permits+1<=host.maxExecutors();
        } catch(BizException | DataAccessException unavailable) { return false; }
    }
    public void lock() {
        if(!enabled()) return;
        if(!TransactionSynchronizationManager.isActualTransactionActive() || mapper.lockResourceGuard()!=1)
            throw new BizException(503,"TEST_CLOUD_RESOURCE_ADMISSION_UNAVAILABLE");
    }
    public void requireNextExecution(TestCloudExecutorService.Identity identity,String keyHash,boolean recovering) {
        requireNextExecution(Path.of("/run/uvel-task-control/capacity.json"),identity,keyHash,recovering);
    }
    void requireNextExecution(Path snapshot,TestCloudExecutorService.Identity identity,String keyHash,boolean recovering) {
        if(!legacyEnabled()) return;
        if(!TransactionSynchronizationManager.isActualTransactionActive()) throw unavailable();
        // reserve() already owns the shared resource guard; preserve guard -> owner -> device ordering.
        if(mapper.lockExecutionOwner(identity.ownerId())==null
                || mapper.lockExecutionDevice(identity.ownerId(),identity.deviceId(),identity.instanceNo())==null)
            throw new BizException(409,"TEST_CLOUD_REQUEST_BINDING_CONFLICT");
        if(mapper.uncertainDeviceRequests(identity.deviceId())!=0) throw new BizException(409,"TEST_CLOUD_EXECUTION_UNRESOLVED");
        int manifests=mapper.unclosedManifests(identity.deviceId());
        if(manifests>0) {
            if(recovering && manifests==1 && mapper.sameLiveNext(identity.ownerId(),identity.deviceId(),identity.instanceNo(),
                    identity.executorId(),identity.taskConfigId(),keyHash,java.time.LocalDateTime.now(clock))==1) return;
            throw new BizException(409,"TEST_CLOUD_EXECUTION_UNRESOLVED");
        }
        HostSnapshot host=readSnapshot(snapshot,v2Enabled());
        if(!host.allowNewActivations() || host.draining() || (long)mapper.actualV1Executions()+v2PhysicalResources()+1>host.maxExecutors())
            throw unavailable();
    }
    public void requireCapacity() {
        requireCapacity(1);
    }
    public void requireCapacity(int additional) {
        requireCapacity(Path.of("/run/uvel-task-control/capacity.json"),additional);
    }
    void requireCapacity(Path snapshot,int additional) {
        if(!enabled()) return;
        if(additional==0) return; // Existing allocations survive a missing or pressured admission snapshot.
        if(additional<0) throw unavailable();
        HostSnapshot host=readSnapshot(snapshot,v2Enabled());
        if(!host.allowNewActivations() || host.draining() || (long)activeV1Resources()+v2PhysicalResources()+additional>host.maxExecutors()) throw unavailable();
    }
    void requireCapacity(Path snapshot) { requireCapacity(snapshot,1); }
    public void requireReplacementCapacity(long sourceDeviceId,int newExecutors) {
        requireReplacementCapacity(Path.of("/run/uvel-task-control/capacity.json"),sourceDeviceId,newExecutors);
    }
    void requireReplacementCapacity(Path snapshot,long sourceDeviceId,int newExecutors) {
        if(!enabled()) return;
        if(newExecutors<0) throw new BizException(503,"TEST_CLOUD_RESOURCE_CAPACITY_UNAVAILABLE");
        int source=logicalDevices().isEmpty() ? mapper.resourceExecutorCount(sourceDeviceId) : mapper.v1ResourceExecutorCount(sourceDeviceId,logicalDevices());
        requireCapacity(snapshot,Math.max(0,newExecutors-source));
    }
    public void requireRestoreCapacity(long deviceId) {
        requireRestoreCapacity(Path.of("/run/uvel-task-control/capacity.json"),deviceId);
    }
    void requireRestoreCapacity(Path snapshot,long deviceId) {
        if(enabled() && !logicalDevices().contains(deviceId)) requireCapacity(snapshot,mapper.restoreResourceDelta(deviceId));
    }
    public void requireActivationCapacity(long deviceId) {
        requireActivationCapacity(Path.of("/run/uvel-task-control/capacity.json"),deviceId);
    }
    void requireActivationCapacity(Path snapshot,long deviceId) {
        if(enabled() && !logicalDevices().contains(deviceId)) requireCapacity(snapshot,mapper.activationResourceDelta(deviceId));
    }
    private static BizException unavailable() { return new BizException(503,"TEST_CLOUD_RESOURCE_CAPACITY_UNAVAILABLE"); }
}
