package ffdd.opsconsole.device.application;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.fasterxml.jackson.databind.json.JsonMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import ffdd.opsconsole.device.compute.v2.ComputeV2Event;
import ffdd.opsconsole.device.compute.v2.ComputeV2Event.*;
import ffdd.opsconsole.device.compute.v2.ComputeV2Runtime;
import ffdd.opsconsole.device.dto.*;
import ffdd.opsconsole.device.mapper.ComputeV2ReadMapper;
import ffdd.opsconsole.shared.api.ApiResult;
import ffdd.opsconsole.shared.exception.BizException;
import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.*;
import java.util.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.mock.env.MockEnvironment;

class ComputeV2ReadServiceTest {
    private static final Instant NOW=Instant.parse("2026-11-01T01:00:00Z");
    private static final String BATCH="a".repeat(64)+":USDT";
    private final AppTaskAssignmentService legacy=mock(AppTaskAssignmentService.class);
    private final ComputeV2Runtime runtime=mock(ComputeV2Runtime.class);
    private final ComputeV2ReadMapper mapper=mock(ComputeV2ReadMapper.class);
    private final MockEnvironment environment=new MockEnvironment().withProperty("nexion.compute-v2.enabled","true")
            .withProperty("nexion.compute-v2.deployment-scope","TEST").withProperty("nexion.compute-v2.device-ids","7");
    private final JsonMapper json=JsonMapper.builder().addModule(new JavaTimeModule()).build();
    private final Clock clock=mock(Clock.class);
    private final ComputeV2ReadService service=new ComputeV2ReadService(legacy,Optional.of(runtime),environment,mapper,json,clock);

    @BeforeEach void prepare() {
        when(clock.instant()).thenReturn(NOW.plusMillis(30125));
        when(legacy.assignments(10L)).thenReturn(ApiResult.ok(original(List.of())));
        when(runtime.view(10L,7L)).thenReturn(Optional.empty());
        when(runtime.pendingVerifiedSnapshot(10L)).thenReturn(new ComputeV2Runtime.PendingVerifiedSnapshot(new ComputeV2Runtime.PendingSummary(0,0,0,0),List.of()));
    }

    @Test void unavailableIsExplicit404BeforeAnyRuntimeOrSqlRead() {
        environment.setProperty("nexion.compute-v2.enabled","false");
        assertThatThrownBy(() -> service.assignments(10L)).isInstanceOfSatisfying(BizException.class,
                ex -> { assertThat(ex.getCode()).isEqualTo(404); assertThat(ex.getMessage()).isEqualTo("COMPUTE_V2_NOT_AVAILABLE"); });
        verifyNoInteractions(runtime,mapper,legacy);
    }

    @Test void unenrolledOwnedDevicesCannotReadAnAllowlistedForeignDevice() {
        environment.setProperty("nexion.compute-v2.device-ids","999");
        assertThatThrownBy(() -> service.assignments(10L)).isInstanceOfSatisfying(BizException.class,ex -> assertThat(ex.getCode()).isEqualTo(404));
        verify(runtime).pendingVerifiedSnapshot(10L); verifyNoMoreInteractions(runtime); verifyNoInteractions(mapper);
    }

    @Test void anAccountWithoutDevicesNeverBuildsAnEmptySqlInList() {
        var response=original(List.of());
        when(legacy.assignments(10L)).thenReturn(ApiResult.ok(new AppTaskAssignmentsResponse(response.serverNow(),List.of(),"server","PRODUCTION","",true)));
        assertThatThrownBy(()->service.assignments(10L)).isInstanceOfSatisfying(BizException.class,ex->assertThat(ex.getCode()).isEqualTo(404));
        verifyNoInteractions(mapper);
    }

    @ParameterizedTest @CsvSource({"ISSUING,QUEUED","STARTING,QUEUED","QUEUED,QUEUED","COMPUTING,RUNNING","VERIFYING,VERIFYING","RECOVERING,RECOVERING","FAILED,FAILED"})
    void activeQuotesAreNotPendingEarningsAndReadsNeverIssueWork(String state,String expected) {
        var event=event(Kind.ISSUED,false,"TEST");
        when(runtime.view(10L,7L)).thenReturn(Optional.of(new ComputeV2Runtime.RuntimeView(event,state,null,null,0,0)));
        var response=service.assignments(10L);
        var current=current(response);
        assertThat(current).containsEntry("status",expected).containsEntry("settlementStatus","NONE")
                .containsEntry("rewardUsdt","0.123456").containsEntry("startedAt",null).containsEntry("actualDurationSec",null)
                .containsEntry("receiptNo",null).containsEntry("protocolVersion",2);
        assertThat(response).containsEntry("deploymentScope","TEST").containsEntry("sourceEnvironment","PRODUCTION");
        assertThat((List<?>)response.get("pendingEarned")).hasSize(2).allSatisfy(row -> assertThat(((Map<?,?>)row).get("amount")).isEqualTo("0.000000"));
        verify(runtime).view(10L,7L); verify(runtime).pendingVerifiedSnapshot(10L); verifyNoMoreInteractions(runtime);
    }

    @ParameterizedTest @CsvSource({"COMPUTING,RUNNING","RUNNING,RUNNING","VERIFYING,VERIFYING","RECOVERING,RECOVERING","FAILING,RECOVERING"})
    void startedWorkShowsServerElapsedWithoutConfirmingCompletion(String state,String expected) {
        var event=event(Kind.STARTED,true,"TEST");
        when(runtime.view(10L,7L)).thenReturn(Optional.of(new ComputeV2Runtime.RuntimeView(event,state,null,null,0,0)));
        var response=service.assignments(10L);
        assertThat(current(response)).containsEntry("status",expected).containsEntry("actualDurationSec",new BigDecimal("20.125"))
                .containsEntry("startedAt",NOW.plusSeconds(10)).containsEntry("requiredSeconds",5)
                .containsEntry("rewardUsdt","0.123456").containsEntry("settlementStatus","NONE").containsEntry("completedAt",null);
        assertThat(event.kind()).isEqualTo(Kind.STARTED);
        assertThat(event.task().rewardUsdtMicros()).isEqualTo(123456);
        assertThat((List<?>)response.get("pendingEarned")).allSatisfy(row->assertThat(((Map<?,?>)row).get("amount")).isEqualTo("0.000000"));
        verify(runtime).view(10L,7L); verify(runtime).pendingVerifiedSnapshot(10L); verifyNoMoreInteractions(runtime);
    }

    @Test void activeDurationAdvancesWithTheServerClockRatherThanCountingDownTheEstimate() {
        var event=event(Kind.STARTED,true,"TEST");
        when(runtime.view(10L,7L)).thenReturn(Optional.of(new ComputeV2Runtime.RuntimeView(event,"COMPUTING",null,null,0,0)));
        when(clock.instant()).thenReturn(NOW.plusMillis(30125),NOW.plusMillis(31125));
        assertThat(current(service.assignments(10L))).containsEntry("actualDurationSec",new BigDecimal("20.125"));
        assertThat(current(service.assignments(10L))).containsEntry("actualDurationSec",new BigDecimal("21.125"))
                .containsEntry("status","RUNNING").containsEntry("settlementStatus","NONE");
    }

    @Test void aFutureStartDoesNotProduceNegativeDurationOrConfirmCompletion() {
        var event=event(Kind.STARTED,true,"TEST");
        when(runtime.view(10L,7L)).thenReturn(Optional.of(new ComputeV2Runtime.RuntimeView(event,"COMPUTING",null,null,0,0)));
        when(clock.instant()).thenReturn(NOW);
        assertThat(current(service.assignments(10L))).containsEntry("actualDurationSec",new BigDecimal("0.000"))
                .containsEntry("status","RUNNING").containsEntry("settlementStatus","NONE").containsEntry("completedAt",null);
    }

    @Test void confirmedCompletionUsesActualStartAndSeparatePerCurrencyCounts() {
        var event=event(Kind.VERIFIED,true,"TEST");
        when(runtime.view(10L,7L)).thenReturn(Optional.of(new ComputeV2Runtime.RuntimeView(event,"COMPLETED",null,null,123456,500000)));
        var older=olderEvent(event,111111,500000);
        when(runtime.pendingVerifiedSnapshot(10L)).thenReturn(new ComputeV2Runtime.PendingVerifiedSnapshot(new ComputeV2Runtime.PendingSummary(234567,500000,2,1),List.of(event,older)));
        var response=service.assignments(10L);
        var history=history(response);
        assertThat(history).hasSize(1);
        assertThat(history.get(0)).containsEntry("status","COMPLETED").containsEntry("settlementStatus","PENDING")
                .containsEntry("actualDurationSec",new BigDecimal("3.500")).containsEntry("startedAt",NOW.plusSeconds(10)).containsEntry("settlementBatchNo",null);
        assertThat(response.get("pendingEarned")).isEqualTo(List.of(
                Map.of("asset","USDT","amount","0.234567","taskCount",2L),Map.of("asset","NEX","amount","0.500000","taskCount",1L)));
        verifyNoInteractions(clock);
    }

    @Test void committedSqlButUnknownAckExcludesEveryPostedEventEvenOutsideVisibleDeviceHistory() {
        var active=event(Kind.VERIFIED,true,"TEST");
        var older=olderEvent(active,111111,500000);
        when(runtime.view(10L,7L)).thenReturn(Optional.of(new ComputeV2Runtime.RuntimeView(active,"COMPLETED",null,null,234567,500000)));
        when(runtime.pendingVerifiedSnapshot(10L)).thenReturn(new ComputeV2Runtime.PendingVerifiedSnapshot(new ComputeV2Runtime.PendingSummary(234567,500000,2,1),List.of(active,older)));
        when(mapper.pendingAllocations(eq(10L),eq("TEST"),eq(List.of(active.eventId(),older.eventId()))))
                .thenReturn(List.of(committedAllocation(older,"USDT",111111),committedAllocation(older,"UVT",500000)));
        var response=service.assignments(10L);
        assertThat(response.get("pendingEarned")).isEqualTo(List.of(
                Map.of("asset","USDT","amount","0.123456","taskCount",1L),Map.of("asset","NEX","amount","0.000000","taskCount",0L)));
        var order=inOrder(runtime,legacy,mapper);
        order.verify(runtime).pendingVerifiedSnapshot(10L); order.verify(legacy).assignments(10L);
        order.verify(mapper).pendingAllocations(10L,"TEST",List.of(active.eventId(),older.eventId()));
    }

    @Test void inconsistentOrForeignPendingSnapshotsFailRatherThanManufacturingZero() {
        var event=event(Kind.VERIFIED,true,"TEST");
        for (var snapshot : List.of(
                new ComputeV2Runtime.PendingVerifiedSnapshot(new ComputeV2Runtime.PendingSummary(123456,0,1,0),List.of()),
                new ComputeV2Runtime.PendingVerifiedSnapshot(new ComputeV2Runtime.PendingSummary(123456,0,1,0),List.of(event(Kind.VERIFIED,true,"OTHER"))),
                new ComputeV2Runtime.PendingVerifiedSnapshot(new ComputeV2Runtime.PendingSummary(246912,0,2,0),List.of(event,event)))) {
            when(runtime.pendingVerifiedSnapshot(10L)).thenReturn(snapshot);
            assertThatThrownBy(() -> service.assignments(10L)).hasMessage("COMPUTE_V2_STATE_INVALID");
        }
        when(runtime.pendingVerifiedSnapshot(10L)).thenReturn(new ComputeV2Runtime.PendingVerifiedSnapshot(new ComputeV2Runtime.PendingSummary(123456,0,1,0),List.of(event)));
        when(mapper.pendingAllocations(eq(10L),eq("TEST"),anyList())).thenReturn(List.of(committedAllocation(event,"USDT",123457)));
        assertThatThrownBy(() -> service.assignments(10L)).hasMessage("COMPUTE_V2_STATE_INVALID");
    }

    @Test void pendingEvidenceReadsAreAccountScopedAndSplitAtTwoHundredEventIds() {
        var original=event(Kind.VERIFIED,true,"TEST");
        var events=new ArrayList<ComputeV2Event>();
        for (int i=0;i<201;i++) events.add(pendingEvent(original,"CV2-PENDING-"+i,8,1,0));
        when(runtime.pendingVerifiedSnapshot(10L)).thenReturn(new ComputeV2Runtime.PendingVerifiedSnapshot(new ComputeV2Runtime.PendingSummary(201,0,201,0),events));
        var result=service.assignments(10L);
        assertThat(result.get("pendingEarned")).isEqualTo(List.of(
                Map.of("asset","USDT","amount","0.000201","taskCount",201L),Map.of("asset","NEX","amount","0.000000","taskCount",0L)));
        var pages=org.mockito.ArgumentCaptor.forClass(List.class);
        verify(mapper,times(2)).pendingAllocations(eq(10L),eq("TEST"),pages.capture());
        assertThat(pages.getAllValues()).extracting(List::size).containsExactly(200,1);
    }

    @Test void runtimeIdentityAndScopeMismatchNeverBecomeCanonicalData() {
        when(runtime.view(10L,7L)).thenReturn(Optional.of(new ComputeV2Runtime.RuntimeView(event(Kind.ISSUED,false,"OTHER"),"QUEUED",null,null,0,0)));
        assertThatThrownBy(() -> service.assignments(10L)).hasMessage("COMPUTE_V2_STATE_INVALID");
        verifyNoInteractions(mapper);
    }

    @Test void deterministicUnpostedVerifiedFailureUsesTheExactGenerationBlockedMarker() {
        var event=event(Kind.VERIFIED,true,"TEST");
        when(runtime.view(10L,7L)).thenReturn(Optional.of(new ComputeV2Runtime.RuntimeView(event,"COMPLETED",null,null,123456,0)));
        when(runtime.pendingVerifiedSnapshot(10L)).thenReturn(new ComputeV2Runtime.PendingVerifiedSnapshot(new ComputeV2Runtime.PendingSummary(123456,0,1,0),List.of(event)));
        when(runtime.settlementBlock(event.taskNo(),event.generation())).thenReturn(Optional.of("COMPUTE_V2_ACCOUNT_NOT_ACTIVE"));
        var response=service.assignments(10L);
        assertThat(history(response).get(0)).containsEntry("settlementStatus","BLOCKED").containsEntry("settlementBlockReason","COMPUTE_V2_ACCOUNT_NOT_ACTIVE");
        assertThat(response.get("pendingEarned")).isEqualTo(List.of(
                Map.of("asset","USDT","amount","0.123456","taskCount",1L),Map.of("asset","NEX","amount","0.000000","taskCount",0L)));
        verify(runtime).settlementBlock(event.taskNo(),event.generation());
    }

    @Test void postedSnapshotReplacesTheLegacyCopyWithoutDuplicateReceipt() throws Exception {
        var event=event(Kind.VERIFIED,true,"TEST");
        when(legacy.assignments(10L)).thenReturn(ApiResult.ok(original(List.of(legacyTask()))));
        when(runtime.view(10L,7L)).thenReturn(Optional.of(new ComputeV2Runtime.RuntimeView(event,"COMPLETED",null,null,0,0)));
        when(runtime.settlementBlock(event.taskNo(),event.generation())).thenReturn(Optional.of("COMPUTE_V2_ACCOUNT_NOT_ACTIVE"));
        when(mapper.postedTasks(eq(10L),eq("TEST"),anyList())).thenReturn(List.of(Map.of("task_no",event.taskNo(),"settlement_id",BATCH,
                "posted_at_utc",timestamp(NOW.plusSeconds(65)),"payload",json.writeValueAsString(event))));
        var response=service.assignments(10L);
        assertThat(history(response)).hasSize(1);
        assertThat(history(response).get(0)).containsEntry("settlementStatus","POSTED").containsEntry("settlementBatchNo",BATCH)
                .containsEntry("settledAt",NOW.plusSeconds(65)).containsEntry("receiptNo",event.receiptNo()).containsEntry("configVersion",3L);
        assertThat(history(response).get(0)).doesNotContainKey("settlementBlockReason");
        verify(runtime,never()).settlementBlock(anyString(),anyLong());
    }

    @Test void settlementHistorySurvivesAdmissionDisableAndReadsStoredWindowRatherThanCompletionTime() {
        environment.setProperty("nexion.compute-v2.enabled","false");
        when(mapper.batches(10L,"TEST",null,null,2)).thenReturn(List.of(batchRow(),batchRow()));
        var result=service.settlements(10L,null,1);
        assertThat(result).containsEntry("nextCursor",BATCH);
        var first=(Map<?,?>)((List<?>)result.get("records")).get(0);
        assertThat(first.get("windowStart")).isEqualTo(NOW);
        assertThat(first.get("windowEnd")).isEqualTo(NOW.plusSeconds(60));
        assertThat(first.get("amount")).isEqualTo("0.123456");
        verify(mapper).batches(10L,"TEST",null,null,2);
        verifyNoInteractions(runtime,legacy);
    }

    @Test void foreignCursorAndUnknownDetailDoNotReadAnotherUsersAllocations() {
        assertThatThrownBy(() -> service.settlements(10L,BATCH,20)).hasMessage("COMPUTE_V2_CURSOR_INVALID");
        assertThatThrownBy(() -> service.settlement(10L,BATCH)).hasMessage("COMPUTE_V2_SETTLEMENT_NOT_FOUND");
        verify(mapper,times(2)).batch(10L,"TEST",BATCH);
        verifyNoMoreInteractions(mapper);
    }

    @Test void detailValidatesAllocationSumAndEveryQueryCarriesUserAndScope() {
        var item=Map.<String,Object>of("task_no","CV2-T1","receipt_no","CTR-T1","user_device_id",7L,"amount_micros",123456L,
                "completed_at_utc",timestamp(NOW.plusSeconds(13)),"task_type","EM");
        when(mapper.batch(10L,"TEST",BATCH)).thenReturn(List.of(batchRow()));
        when(mapper.allocations(10L,"TEST",BATCH)).thenReturn(List.of(item));
        var detail=service.settlement(10L,BATCH);
        assertThat((List<?>)detail.get("items")).hasSize(1);
        assertThat(((Map<?,?>)((List<?>)detail.get("items")).get(0)).get("amount")).isEqualTo("0.123456");
        verify(mapper).allocations(10L,"TEST",BATCH);
        var wrong=new HashMap<>(item); wrong.put("amount_micros",123457L);
        when(mapper.allocations(10L,"TEST",BATCH)).thenReturn(List.of(wrong));
        assertThatThrownBy(() -> service.settlement(10L,BATCH)).hasMessage("COMPUTE_V2_STATE_INVALID");
    }

    @Test void unsupportedProfileCannotExposeBusinessHistory() {
        environment.setActiveProfiles("sandbox");
        assertThatThrownBy(() -> service.settlements(10L,null,20)).hasMessage("COMPUTE_V2_RUNTIME_UNSUPPORTED");
        verifyNoInteractions(mapper);
    }

    @ParameterizedTest @CsvSource({"UNKNOWN,0,true","UNKNOWN,600,true","PROCESSING,599,false","PROCESSING,600,false","PROCESSING,601,true","COMMITTED,3600,false","ROLLED_BACK,3600,false"})
    void legacyRecoveryUsesTheBusinessClockAndStrictTenMinuteBoundary(String state,int ageSeconds,boolean recovering) {
        var current=oldTask("CT-CURRENT","RUNNING");
        var original=withCurrent(current);
        when(legacy.assignments(10L)).thenReturn(ApiResult.ok(original));
        when(mapper.legacyCompletionRequests(10L,List.of(7L,8L))).thenReturn(List.of(
                new ComputeV2ReadMapper.LegacyCompletionRequest("CT-CURRENT",7L,"INSTANCE-7",state,original.serverNow().minusSeconds(ageSeconds))));
        var response=service.assignments(10L);
        assertThat(current(response)).containsEntry("status",recovering?"RECOVERING":"RUNNING")
                .containsEntry("completableAt",recovering?null:current.completableAt()).containsEntry("receiptNo",null)
                .containsEntry("settlementStatus","NONE").containsEntry("actualDurationSec",null);
        assertThat(device(response)).containsEntry("recoveryPending",recovering);
        assertThat(original.devices().get(0).currentTask().status()).isEqualTo("RUNNING");
        assertThat((List<?>)response.get("pendingEarned")).allSatisfy(row->assertThat(((Map<?,?>)row).get("amount")).isEqualTo("0.000000"));
        verify(mapper).legacyCompletionRequests(10L,List.of(7L,8L));
        verifyNoMoreInteractions(mapper);
    }

    @Test void expiredLeaseStillHasADeviceRecoveryFactWithoutManufacturingACurrentTask() {
        var original=original(List.of());
        when(mapper.legacyCompletionRequests(10L,List.of(7L,8L))).thenReturn(List.of(
                new ComputeV2ReadMapper.LegacyCompletionRequest("CT-EXPIRED",7L,"INSTANCE-7","PROCESSING",original.serverNow().minusHours(6))));
        var response=service.assignments(10L);
        assertThat(current(response)).isNull();
        assertThat(device(response)).containsEntry("recoveryPending",true).containsEntry("recoveryReason","LEGACY_COMPLETE_OUTCOME_UNKNOWN");
        assertThat(history(response)).isEmpty();
    }

    @Test void historicalUnknownDoesNotPoisonAnotherCurrentTaskOrACompletedReceipt() {
        when(legacy.assignments(10L)).thenReturn(ApiResult.ok(withCurrent(oldTask("CT-NEW","RUNNING"))));
        when(mapper.legacyCompletionRequests(10L,List.of(7L,8L))).thenReturn(List.of(
                new ComputeV2ReadMapper.LegacyCompletionRequest("CT-OLD",7L,"INSTANCE-7","UNKNOWN",LocalDateTime.MIN)));
        var response=service.assignments(10L);
        assertThat(current(response)).containsEntry("status","RUNNING");
        assertThat(device(response)).containsEntry("recoveryPending",false);
        when(legacy.assignments(10L)).thenReturn(ApiResult.ok(withCurrent(legacyTask())));
        when(mapper.legacyCompletionRequests(10L,List.of(7L,8L))).thenReturn(List.of(
                new ComputeV2ReadMapper.LegacyCompletionRequest("CT-OLD",7L,"INSTANCE-7","UNKNOWN",LocalDateTime.MIN)));
        response=service.assignments(10L);
        assertThat(current(response)).containsEntry("status","COMPLETED").containsEntry("receiptNo","CTR-T1").containsEntry("settlementStatus","POSTED");
        assertThat(device(response)).containsEntry("recoveryPending",false);
    }

    @Test void aForeignDeviceOrReplacedInstanceCannotBecomeARecoveryFact() {
        for (var row : List.of(
                new ComputeV2ReadMapper.LegacyCompletionRequest("CT-OLD",99L,"INSTANCE-99","UNKNOWN",LocalDateTime.MIN),
                new ComputeV2ReadMapper.LegacyCompletionRequest("CT-OLD",7L,"OLD-INSTANCE-7","UNKNOWN",LocalDateTime.MIN))) {
            when(mapper.legacyCompletionRequests(10L,List.of(7L,8L))).thenReturn(List.of(row));
            assertThatThrownBy(()->service.assignments(10L)).hasMessage("COMPUTE_V2_STATE_INVALID");
        }
    }

    private AppTaskAssignmentsResponse withCurrent(AppTaskAssignmentView current) {
        var response=original(List.of());
        return new AppTaskAssignmentsResponse(response.serverNow(),List.of(
                new AppTaskDeviceState(7L,"INSTANCE-7","CLOUD_SHARE",null,current,List.of()),response.devices().get(1)),"server","PRODUCTION","",true);
    }
    private AppTaskAssignmentView oldTask(String taskNo,String status) {
        var start=LocalDateTime.ofInstant(NOW,ZoneOffset.ofHours(8)).minusHours(6);
        return new AppTaskAssignmentView(taskNo,7L,"EM-1","Embedding","EM","BGE-M3","TEST",status,new BigDecimal("0.123456"),5,
                start,start.plusSeconds(5),null,null,null,null,"server","PRODUCTION","",true,null,0);
    }
    @SuppressWarnings("unchecked") private Map<String,Object> device(Map<String,Object> response) { return (Map<String,Object>)((List<?>)response.get("devices")).get(0); }

    private AppTaskAssignmentsResponse original(List<AppTaskAssignmentView> recent) {
        return new AppTaskAssignmentsResponse(LocalDateTime.ofInstant(NOW,ZoneOffset.ofHours(8)),List.of(
                new AppTaskDeviceState(7L,"INSTANCE-7","CLOUD_SHARE",null,null,recent),
                new AppTaskDeviceState(8L,"INSTANCE-8","MOBILE",null,null,List.of())),"server","PRODUCTION","",true);
    }
    private AppTaskAssignmentView legacyTask() {
        return new AppTaskAssignmentView("CV2-T1",7L,"EM-1","Embedding","EM","BGE-M3","TEST","COMPLETED",new BigDecimal("0.123456"),5,
                LocalDateTime.ofInstant(NOW.plusSeconds(10),ZoneOffset.ofHours(8)),LocalDateTime.ofInstant(NOW.plusSeconds(15),ZoneOffset.ofHours(8)),
                LocalDateTime.ofInstant(NOW.plusMillis(13500),ZoneOffset.ofHours(8)),"CTR-T1",null,null,"server","PRODUCTION","",true,null,0);
    }
    private ComputeV2Event event(Kind kind,boolean started,String scope) {
        var task=new TaskSnapshot("request","EM-1","Embedding","EM","BGE-M3","TEST",3,5,5,5,5,120,0,NOW,started?NOW.plusSeconds(10):null,
                NOW.plusSeconds(120),NOW.plusSeconds(180),123456,123456,BigDecimal.ONE,100,"00000000-0000-0000-0000-000000000001",
                new DeviceSnapshot("Cloud","CLOUD_SHARE","cloud-share","GPU",1,BigDecimal.ONE,8),"a".repeat(64),"b".repeat(64),"compute/input");
        var proof=kind==Kind.VERIFIED ? new Verified("c".repeat(64),"a".repeat(64),"d".repeat(64),NOW.plusSeconds(14),NOW.plusMillis(13500),"compute/result",100,0,null,LocalDate.parse("2026-11-01")) : null;
        return new ComputeV2Event(2,ComputeV2Event.eventId("CV2-T1",1,kind),kind,scope,"PRODUCTION","",10,7,"INSTANCE-7","CV2-T1",1,NOW.plusSeconds(14),task,proof,null);
    }
    private Map<String,Object> batchRow() {
        return Map.of("settlement_id",BATCH,"asset","USDT","total_micros",123456L,"task_count",1L,
                "window_start_utc",timestamp(NOW),"window_end_utc",timestamp(NOW.plusSeconds(60)),"posted_at_utc",timestamp(NOW.plusSeconds(65)));
    }
    private Timestamp timestamp(Instant value) { return Timestamp.valueOf(LocalDateTime.ofInstant(value,ZoneOffset.UTC)); }
    private ComputeV2Event olderEvent(ComputeV2Event original,long usdt,long nex) {
        return pendingEvent(original,"CV2-OLDER",8,usdt,nex);
    }
    private ComputeV2Event pendingEvent(ComputeV2Event original,String taskNo,long deviceId,long usdt,long nex) {
        var t=original.task();
        var task=new TaskSnapshot("request-"+taskNo,t.taskConfigId(),t.taskName(),t.taskClass(),t.modelName(),t.clientName(),t.configVersion(),t.expectedSeconds(),t.minSeconds(),t.maxSeconds(),t.requiredSeconds(),t.executionTimeoutSec(),t.taskLockMinutes(),t.issuedAt(),t.startedAt(),t.leaseExpiresAt(),t.proofExpiresAt(),usdt,usdt,t.e3Multiplier(),t.walletId(),t.walletIncarnation(),t.device(),t.inputSha256(),t.modelSha256(),t.inputArtifactRef());
        var p=original.evidence();
        var proof=new Verified(p.proofHash(),p.inputSha256(),p.resultSha256(),p.verifiedAt(),p.completedAt(),p.artifactRef(),p.artifactBytes(),nex,nex>0?"CLOUD_SHARE_DAILY:"+deviceId+":2026-11-01":null,p.utcDay());
        return new ComputeV2Event(2,ComputeV2Event.eventId(taskNo,1,Kind.VERIFIED),Kind.VERIFIED,"TEST","PRODUCTION","",10,deviceId,"INSTANCE-"+deviceId,taskNo,1,original.occurredAt(),task,proof,null);
    }
    private Map<String,Object> committedAllocation(ComputeV2Event event,String asset,long micros) {
        return Map.of("event_id",event.eventId(),"asset",asset,"amount_micros",micros,"task_no",event.taskNo(),"receipt_no",event.receiptNo(),"user_device_id",event.deviceId());
    }
    @SuppressWarnings("unchecked") private Map<String,Object> current(Map<String,Object> response) { return (Map<String,Object>)((Map<?,?>)((List<?>)response.get("devices")).get(0)).get("currentTask"); }
    @SuppressWarnings("unchecked") private List<Map<String,Object>> history(Map<String,Object> response) { return (List<Map<String,Object>>)((Map<?,?>)((List<?>)response.get("devices")).get(0)).get("recentTasks"); }
}
