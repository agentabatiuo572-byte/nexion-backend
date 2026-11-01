package ffdd.opsconsole.device.application;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

import ffdd.opsconsole.device.compute.v2.*;
import ffdd.opsconsole.device.compute.v2.ComputeV2Event.*;
import ffdd.opsconsole.finance.compute.v2.ComputeV2SettlementMySqlIntegrationTest;
import ffdd.opsconsole.shared.audit.AuditLogService;
import ffdd.opsconsole.shared.canonical.mapper.CanonicalStateMapper;
import ffdd.opsconsole.shared.canonical.mapper.CanonicalStateMapper.UserDeviceCommandRow;
import ffdd.opsconsole.shared.outbox.EventOutboxService;
import java.time.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.*;
import org.springframework.mock.env.MockEnvironment;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.*;

class ComputeV2DeviceLifecycleServiceTest {
    private final ComputeV2Runtime runtime=mock(ComputeV2Runtime.class);
    private final CanonicalStateMapper mapper=mock(CanonicalStateMapper.class);
    private final AuditLogService audit=mock(AuditLogService.class);
    private final EventOutboxService outbox=mock(EventOutboxService.class);
    private final PlatformTransactionManager manager=mock(PlatformTransactionManager.class);
    private final MockEnvironment environment=new MockEnvironment().withProperty("nexion.compute-v2.device-ids","11")
            .withProperty("nexion.compute-v2.deployment-scope","TEST");
    private final ComputeV2Event verified=new ComputeV2SettlementMySqlIntegrationTest().completion(1,1,0,Instant.parse("2026-11-01T00:00:00Z"));
    private ComputeV2DeviceLifecycleService lifecycle;
    private final AtomicReference<UserDeviceCommandRow> row=new AtomicReference<>();
    private final AtomicReference<ComputeV2Runtime.RuntimeView> view=new AtomicReference<>();

    @BeforeEach void setup() {
        lifecycle=new ComputeV2DeviceLifecycleService(Optional.of(runtime),mapper,manager,audit,outbox,environment);
        row.set(device(false));view.set(runtimeView("COMPUTING","compute-v2:runner-1",false));
        when(runtime.isReady()).thenReturn(true);when(runtime.viewDevice(11)).thenAnswer(i->Optional.ofNullable(view.get()));
        when(mapper.lockUser(7L)).thenReturn(new CanonicalStateMapper.UserLock(7L,false));
        when(mapper.lockDeviceForUserCommand(11L)).thenAnswer(i->row.get());
        when(mapper.markDevicePendingDeactivate(7L,11L,5L)).thenAnswer(i->{row.set(device(true));return 1;});
        when(mapper.userEventAttribution(7L)).thenReturn(new CanonicalStateMapper.UserEventAttribution("P1",1,"2026-W44"));
        when(mapper.deactivatePendingComputeV2Device(7,11,5L)).thenReturn(1);
        when(manager.getTransaction(any())).thenReturn(new SimpleTransactionStatus());
    }
    @AfterEach void cleanup(){if(TransactionSynchronizationManager.isSynchronizationActive())TransactionSynchronizationManager.clearSynchronization();TransactionSynchronizationManager.setActualTransactionActive(false);}

    @Test void runningDeactivationOnlySetsPendingAndNeverCancelsOrRecyclesTheInference() {
        begin();assertThat(lifecycle.deferDeactivation(7,11,5L)).isTrue();
        verify(mapper).markDevicePendingDeactivate(7L,11L,5L);
        verify(runtime,never()).cancelQueued(anyLong(),anyLong(),anyLong(),anyString());
        commitCallbacks();
        verify(mapper,never()).deactivatePendingComputeV2Device(anyLong(),anyLong(),any());
        verify(mapper,never()).cancelActiveDeviceTasks(any(),any());
    }

    @Test void queuedCancellationWaitsForSqlCommitAndOnlyThenFinalizesThePendingDevice() {
        view.set(runtimeView("QUEUED",null,true));
        when(runtime.cancelQueued(7,11,1,"USER_DEACTIVATION_PENDING")).thenAnswer(i->{view.set(runtimeView("FAILED",null,true));return true;});
        begin();assertThat(lifecycle.deferDeactivation(7,11,5L)).isTrue();
        verify(runtime,never()).cancelQueued(anyLong(),anyLong(),anyLong(),anyString());
        commitCallbacks();verify(runtime).cancelQueued(7,11,1,"USER_DEACTIVATION_PENDING");
        verify(mapper).deactivatePendingComputeV2Device(7,11,5L);
        verify(outbox).publishUserEvent(eq("USER_DEVICE"),eq("DEVICE-11"),eq("device.deactivated"),eq(7L),eq("P1"),eq(1),eq("2026-W44"),any());
    }

    @Test void queuedCancelLosingAcquireRaceRetainsPendingAndActualExecutionPermit() {
        view.set(runtimeView("QUEUED",null,true));
        when(runtime.cancelQueued(anyLong(),anyLong(),anyLong(),anyString())).thenAnswer(i->{view.set(runtimeView("COMPUTING","compute-v2:runner-1",false));return false;});
        begin();lifecycle.deferDeactivation(7,11,5L);commitCallbacks();
        verify(mapper,never()).deactivatePendingComputeV2Device(anyLong(),anyLong(),any());
        verify(runtime,never()).executionExited(anyLong(),anyLong(),anyLong(),anyString());
    }

    @Test void rollbackDoesNotPublishQueuedCancellation() {
        view.set(runtimeView("QUEUED",null,true));begin();lifecycle.deferDeactivation(7,11,5L);
        for(var callback:TransactionSynchronizationManager.getSynchronizations())callback.afterCompletion(TransactionSynchronization.STATUS_ROLLED_BACK);
        verify(runtime,never()).cancelQueued(anyLong(),anyLong(),anyLong(),anyString());
    }

    @Test void aStartedGenerationRequiresExactExitedEvidenceAndUnknownV1StillBlocks() {
        row.set(device(true));view.set(runtimeView("FAILED",null,false));
        assertThat(lifecycle.finalizeExited(7,11,verified.taskNo(),1)).isFalse();
        when(runtime.hasExited(verified.taskNo(),1)).thenReturn(true);
        when(mapper.hasUnresolvedComputeV1Request(7,11)).thenReturn(true);
        assertThat(lifecycle.finalizeExited(7,11,verified.taskNo(),1)).isFalse();
        verify(mapper,never()).deactivatePendingComputeV2Device(anyLong(),anyLong(),any());
        when(mapper.hasUnresolvedComputeV1Request(7,11)).thenReturn(false);
        assertThat(lifecycle.finalizeExited(7,11,verified.taskNo(),1)).isTrue();
        verify(mapper,never()).cancelActiveDeviceTasks(any(),any());
    }

    @Test void replacementAndUnbindRejectAnUndrainedOrForeignGeneration() {
        assertThatThrownBy(()->lifecycle.requireDrainedForReplacement(7,11)).hasMessage("COMPUTE_V2_DEVICE_BUSY");
        assertThatThrownBy(()->lifecycle.requireDrainedForReplacement(8,11)).hasMessage("COMPUTE_V2_OWNER_CHANGED");
        verify(mapper,never()).markDevicePendingDeactivate(any(),any(),any());
    }

    @Test void redisRecoveryBlocksEnrolledLifecycleButOrdinaryNonenrolledDeviceUsesExistingPath() {
        when(runtime.isReady()).thenReturn(false);
        assertThatThrownBy(()->lifecycle.deferDeactivation(7,11,5L)).hasMessage("COMPUTE_V2_RUNTIME_RECOVERING");
        assertThat(lifecycle.deferDeactivation(7,99,5L)).isFalse();
        lifecycle.requireDrainedForReplacement(7,99);
        verify(runtime,never()).viewDevice(99);
        var disabled=new ComputeV2DeviceLifecycleService(Optional.empty(),mapper,manager,audit,outbox,environment);
        assertThat(disabled.deferDeactivation(7,11,5L)).isFalse();
    }

    @Test void unresolvedV1WithoutAnyRedisTaskIsPreservedForAnEnrolledDevice() {
        view.set(null);when(mapper.hasUnresolvedComputeV1Request(7,11)).thenReturn(true);
        assertThatThrownBy(()->lifecycle.deferDeactivation(7,11,5L)).hasMessage("COMPUTE_V2_LEGACY_REQUEST_UNRESOLVED");
        assertThatThrownBy(()->lifecycle.requireDrainedForReplacement(7,11)).hasMessage("COMPUTE_V2_LEGACY_REQUEST_UNRESOLVED");
        verify(mapper,never()).markDevicePendingDeactivate(any(),any(),any());
    }

    private void begin(){TransactionSynchronizationManager.initSynchronization();TransactionSynchronizationManager.setActualTransactionActive(true);}
    private void commitCallbacks(){for(var callback:List.copyOf(TransactionSynchronizationManager.getSynchronizations()))callback.afterCommit();}
    private UserDeviceCommandRow device(boolean pending){return new UserDeviceCommandRow(11L,7L,"DEVICE-11","ACTIVE","OWNED",5L,pending,"SERVER",LocalDateTime.of(2026,1,1,0,0),null);}
    private ComputeV2Runtime.RuntimeView runtimeView(String status,String executor,boolean queued){
        var t=verified.task();
        if(queued)t=new TaskSnapshot(t.requestId(),t.taskConfigId(),t.taskName(),t.taskClass(),t.modelName(),t.clientName(),t.configVersion(),t.expectedSeconds(),t.minSeconds(),t.maxSeconds(),t.requiredSeconds(),t.executionTimeoutSec(),0,t.issuedAt(),null,t.leaseExpiresAt(),t.proofExpiresAt(),t.rewardUsdtMicros(),t.rawQuoteMicros(),t.e3Multiplier(),t.walletId(),t.walletIncarnation(),t.device(),t.inputSha256(),t.modelSha256(),t.inputArtifactRef());
        Kind kind=queued?Kind.ISSUED:Kind.STARTED;
        var event=new ComputeV2Event(2,ComputeV2Event.eventId(verified.taskNo(),1,kind),kind,"TEST","PRODUCTION","",7,11,"DEVICE-11",verified.taskNo(),1,verified.occurredAt(),t,null,null);
        return new ComputeV2Runtime.RuntimeView(event,status,executor,t.leaseExpiresAt(),0,0);
    }
}
