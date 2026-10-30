package ffdd.opsconsole.device.application;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;
import static ffdd.opsconsole.device.application.TestCloudExecutorService.*;
import ffdd.opsconsole.device.mapper.*;
import ffdd.opsconsole.device.mapper.AppTaskAssignmentMapper.*;
import ffdd.opsconsole.device.mapper.TestCloudExecutorMapper.Execution;
import ffdd.opsconsole.device.mapper.TestCloudExecutorMapper.LegacyAdmission;
import ffdd.opsconsole.device.dto.AppTaskAssignmentView;
import ffdd.opsconsole.device.application.TestCloudExecutorService.*;
import ffdd.opsconsole.shared.api.ApiResult;
import ffdd.opsconsole.shared.audit.AuditLogService;
import java.math.BigDecimal;
import java.time.*;
import java.util.*;
import org.junit.jupiter.api.*;
import org.springframework.mock.env.MockEnvironment;

class TestCloudExecutorServiceTest {
    static final Instant INSTANT=Instant.parse("2026-10-30T01:00:00Z");
    static final LocalDateTime NOW=LocalDateTime.ofInstant(INSTANT,ZoneOffset.UTC);
    static final String NONCE="a".repeat(64), CLAIM="b".repeat(64), TASK="CTA-CLOUD-TEST";
    static final String DIGEST="sha256:7907646426070047a77226ac3e684fbbe8410524f7b4a74d02837e43f2146bab";
    static final String TOKEN="ct1_"+Base64.getUrlEncoder().withoutPadding().encodeToString(new byte[32]);
    final AppTaskAssignmentMapper assignments=mock(AppTaskAssignmentMapper.class);
    final TestCloudExecutorMapper mapper=mock(TestCloudExecutorMapper.class);
    final AppTaskAssignmentService settlement=mock(AppTaskAssignmentService.class);
    final AuditLogService audit=mock(AuditLogService.class);
    final MockEnvironment env=environment();
    final TestCloudExecutorService service=spy(new TestCloudExecutorService(assignments,mapper,settlement,audit,Clock.fixed(INSTANT,ZoneOffset.UTC),env));
    Identity identity;
    static MockEnvironment environment() {
        var env=new MockEnvironment(); env.setActiveProfiles("dev"); env.setProperty("server.forward-headers-strategy","none");
        Map.ofEntries(Map.entry("enabled","true"),Map.entry("deployment-scope","TEST"),Map.entry("executor-id","cloud-unit"),
                Map.entry("owner-id","7"),Map.entry("device-id","11"),Map.entry("instance-no","NEX-UNIT-CLOUD"),
                Map.entry("task-config-id","EM-UNIT"),Map.entry("model","bge-m3:567m"),Map.entry("model-digest",DIGEST),
                Map.entry("credential-sha256",TestCloudExecutorService.sha256(new byte[32])))
                .forEach((key,value)->env.setProperty(PREFIX+key,value));
        return env;
    }
    static DeviceRow device() { return new DeviceRow(11L,"NEX-UNIT-CLOUD","SHARE","SHARE","Cloud", "ACTIVE","cloud-share",NOW.minusDays(1),NOW.minusHours(1),24,"unit","ONLINE",null,false); }
    static AssignmentRow task(String status) { return new AssignmentRow(TASK,11L,"EM-UNIT","UVEL TEST BGE-M3 inference","EM",KIND,CLIENT,status,new BigDecimal("0.045005"),5,0,NOW.minusSeconds(10),NOW.plusHours(24),null,null,NONCE,NOW.plusHours(24)); }
    static String inputJson() { return "[\"UVEL TEST: Cloud Share inference acceptance.\",\"This text is a TEST workload, not a customer order.\",\"多语言向量嵌入验收：仅用于TEST环境。\"]"; }
    static Execution execution(String hash,String keyHash,String bodyHash) { return new Execution(TASK,"cloud-unit",7,11,"NEX-UNIT-CLOUD","EM-UNIT","c".repeat(64),CLAIM,inputJson(),TestCloudExecutorService.sha256(inputJson()),"bge-m3:567m",DIGEST,NONCE,hash,null,keyHash,bodyHash,NOW); }
    @BeforeEach void setup() {
        identity=service.authenticate("Bearer "+TOKEN);
        when(assignments.lockProductionUser(7L)).thenReturn(7L); when(assignments.lockOwnedDevice(7L,11L)).thenReturn(device());
        when(assignments.lockPaidCloudShareDailyNex(7L,11L)).thenReturn(new BigDecimal("3"));
        when(mapper.eligibleBinding(7,11,"NEX-UNIT-CLOUD")).thenReturn(1);
        when(assignments.lockAssignment(7L,TASK,"PRODUCTION")).thenReturn(task("RUNNING"));
        when(mapper.lock(TASK)).thenReturn(execution(null,null,null));
        when(assignments.lockTestWorkerRuntime(7L,11L,"NEX-UNIT-CLOUD")).thenReturn(new TestWorkerRuntimeRow("ONLINE",TASK,CLIENT,"test-cloud:cloud-unit",NOW,null));
    }
    CompleteRequest request(float value) throws Exception {
        byte[] bytes=TestCloudBgeArtifactTest.artifact(value);
        return new CompleteRequest(SPEC,TestCloudExecutorService.sha256(inputJson()),DIGEST,Base64.getEncoder().encodeToString(bytes),
                TestCloudBgeArtifactTest.hash(bytes),NONCE,INSTANT.toEpochMilli(),CLAIM);
    }
    @Test void defaultOffProfileScopeTokenAndModelBindingAreEnforced() {
        env.setProperty(PREFIX+"enabled","false"); assertThatThrownBy(service::requireEnabled).hasMessage("TEST_CLOUD_DISABLED");
        env.setProperty(PREFIX+"enabled","true"); env.setActiveProfiles("prod");
        assertThatThrownBy(service::requireEnabled).hasMessage("TEST_CLOUD_DISABLED"); env.setActiveProfiles("dev");
        env.setProperty(PREFIX+"deployment-scope","PRODUCTION"); assertThatThrownBy(service::requireEnabled).hasMessage("TEST_CLOUD_DISABLED");
        env.setProperty(PREFIX+"deployment-scope","TEST");
        for(String token:List.of("Bearer user-jwt","Bearer tc1_"+TOKEN.substring(4),"Bearer tw1_"+TOKEN.substring(4))) {
            assertThatThrownBy(()->service.authenticate(token)).hasMessage("TEST_CLOUD_AUTH_INVALID");
        }
        env.setProperty(PREFIX+"model-digest","sha256:"+"1".repeat(64));
        assertThatThrownBy(()->service.authenticate("Bearer "+TOKEN)).hasMessage("TEST_CLOUD_AUTH_INVALID");
    }
    private Identity fleetIdentity() {
        env.setProperty(PREFIX+"fleet-enabled","true"); env.setProperty(PREFIX+"fleet-started-at",NOW.minusDays(1).toString());
        var b=new TestCloudExecutorMapper.Binding("fleet-12",7,12,"NEX-FLEET-12","EM-UNIT","bge-m3:567m",DIGEST);
        when(mapper.binding("fleet-12")).thenReturn(b);
        return service.authenticate("Bearer "+TOKEN,"fleet-12");
    }
    @Test void fleetDiscoveryIsBoundedReadOnlyAndDoesNotTrustClientOwnership() {
        fleetIdentity();
        when(mapper.fleetCandidates(NOW.minusDays(1),null,0,11,2,false)).thenReturn(List.of(
                new TestCloudExecutorMapper.FleetCandidate(7,12,"NEX-FLEET-12"),
                new TestCloudExecutorMapper.FleetCandidate(8,13,"NEX-FLEET-13")));
        var fleet=service.authenticate("Bearer "+TOKEN,"fleet");
        var data=service.instances(fleet,0,1).getData();
        assertThat(data.get("hasMore")).isEqualTo(true); assertThat(data.get("nextAfterDeviceId")).isEqualTo(12L);
        assertThat((List<?>)data.get("instances")).hasSize(1);
        verify(mapper,never()).register(any(),any()); verifyNoInteractions(settlement);
        assertThatThrownBy(()->service.authenticate("Bearer "+TOKEN,"fleet-13")).hasMessage("TEST_CLOUD_AUTH_INVALID");
    }
    @Test void paidFleetLifecycleIsCheckedUnderTheExistingDeviceLockBeforeClaim() {
        Identity target=fleetIdentity();
        when(assignments.lockOwnedDevice(7L,12L)).thenReturn(new DeviceRow(12L,"NEX-FLEET-12","DEVICE","S1","S1",
                "ACTIVE","stellarbox-s1",NOW.minusDays(1),NOW.minusHours(1),96,"unit","ONLINE",null,false));
        when(mapper.liveFleetBinding(NOW.minusDays(1),null,7,12,"NEX-FLEET-12",false,false)).thenReturn(null);
        assertThatThrownBy(()->service.next(target,"fleet-next-unit")).hasMessage("TEST_CLOUD_BINDING_INVALID");
        verifyNoInteractions(settlement); verify(mapper,never()).register(any(),any());
    }
    @Test void tradeinIsDisabledByDefaultAndUsesItsIndependentBusinessTimeCutoffWhenEnabled() {
        fleetIdentity();
        assertThat(tradeinFleetCutoff(env)).isNull();
        env.setProperty(PREFIX+"fleet-tradein-enabled","true");
        assertThatThrownBy(()->service.authenticate("Bearer "+TOKEN,"fleet-13")).hasMessage("TEST_CLOUD_AUTH_INVALID");
        var tradeinCutoff=NOW.minusHours(1);
        env.setProperty(PREFIX+"fleet-tradein-started-at",tradeinCutoff.toString());
        when(mapper.fleetCandidate(NOW.minusDays(1),tradeinCutoff,13,11,false))
                .thenReturn(new TestCloudExecutorMapper.FleetCandidate(7,13,"DEV-TI-UNIT"));
        assertThat(service.authenticate("Bearer "+TOKEN,"fleet-13").deviceId()).isEqualTo(13);
        env.setProperty(PREFIX+"fleet-tradein-enabled","false");
        assertThatThrownBy(()->service.authenticate("Bearer "+TOKEN,"fleet-13")).hasMessage("TEST_CLOUD_AUTH_INVALID");
    }
    @Test void everyFleetMutationRechecksTheSameTradeinAuthorityBeforeWorkerOrSettlementWrites() {
        Identity target=fleetIdentity();
        env.setProperty(PREFIX+"fleet-tradein-enabled","true");
        var tradeinCutoff=NOW.minusHours(1);
        env.setProperty(PREFIX+"fleet-tradein-started-at",tradeinCutoff.toString());
        when(assignments.lockOwnedDevice(7L,12L)).thenReturn(new DeviceRow(12L,"NEX-FLEET-12","DEVICE","S1","S1",
                "ACTIVE","stellarbox-s1",NOW.minusDays(1),NOW.minusHours(1),96,"unit","ONLINE",null,false));
        var original=execution(null,null,null);
        when(mapper.lock(TASK)).thenReturn(new Execution(TASK,"fleet-12",7,12,"NEX-FLEET-12","EM-UNIT",
                original.nextKeyHash(),CLAIM,original.inputJson(),original.inputHash(),original.modelTag(),original.modelDigest(),
                NONCE,null,null,null,null,NOW));
        when(assignments.lockAssignment(7L,TASK,"PRODUCTION")).thenReturn(new AssignmentRow(TASK,12L,"EM-UNIT",
                TASK_NAME,"EM",KIND,CLIENT,"RUNNING",BigDecimal.ONE,5,0,NOW.minusSeconds(10),NOW.plusHours(24),null,null,NONCE,NOW.plusHours(24)));
        assertThatThrownBy(()->service.next(target,"fleet-next-unit")).hasMessage("TEST_CLOUD_BINDING_INVALID");
        assertThatThrownBy(()->service.heartbeat(target,null,null)).hasMessage("TEST_CLOUD_BINDING_INVALID");
        assertThatThrownBy(()->service.complete(target,TASK,"fleet-complete-unit",null)).hasMessage("TEST_CLOUD_BINDING_INVALID");
        verify(mapper,times(2)).liveFleetBinding(NOW.minusDays(1),tradeinCutoff,7,12,"NEX-FLEET-12",false,false);
        verify(mapper).liveFleetBinding(NOW.minusDays(1),tradeinCutoff,7,12,"NEX-FLEET-12",true,false);
        verify(mapper,never()).heartbeat(anyLong(),any(),any(),any()); verifyNoInteractions(settlement);
    }
    @Test void fleetReceiptReconciliationSurvivesRevokedOwnershipAndNeverClaimsOrCredits() {
        Identity target=fleetIdentity();
        var original=execution(null,null,null);
        var execution=new Execution(TASK,"fleet-12",7,12,"NEX-FLEET-12","EM-UNIT",original.nextKeyHash(),CLAIM,
                original.inputJson(),original.inputHash(),original.modelTag(),original.modelDigest(),NONCE,null,null,null,null,NOW);
        when(mapper.lock(TASK)).thenReturn(execution);
        when(mapper.historicalReceipt(TASK,7,12)).thenReturn(new TestCloudExecutorMapper.HistoricalReceipt(
                "RUNNING","EM-UNIT",KIND,CLIENT,null,null,null));
        var data=service.receipt(target,TASK).getData(); assertThat(data.get("found")).isEqualTo(false);
        verify(assignments,never()).lockOwnedDevice(7L,12L); verifyNoInteractions(settlement);
    }
    @Test void disabledFleetStillExpiresOnlyItsPreciselyRegisteredStaleRuntimeMarkers() {
        fleetIdentity(); env.setProperty(PREFIX+"fleet-enabled","false");
        when(mapper.bindings()).thenReturn(List.of(new TestCloudExecutorMapper.Binding("fleet-12",7,12,"NEX-FLEET-12","EM-UNIT","bge-m3:567m",DIGEST)));
        service.expireHeartbeat();
        verify(mapper).expireHeartbeat(7,12,"NEX-FLEET-12","test-cloud:fleet-12",NOW,NOW.minusSeconds(120));
        verifyNoInteractions(settlement);
    }
    @Test void authoritativeForeignOrUnpaidOrPendingDeviceIsRejectedBeforeClaim() {
        when(mapper.eligibleBinding(7,11,"NEX-UNIT-CLOUD")).thenReturn(0);
        assertThatThrownBy(()->service.next(identity,"cloud-next-unit")).hasMessage("TEST_CLOUD_BINDING_INVALID");
        verifyNoInteractions(settlement); verify(mapper,never()).insert(any(Execution.class));
    }
    @Test void undeclaredUnmanifestedOldTaskIsNeverCancelledOrRebound() {
        when(assignments.lockActiveAssignment(7L,11L,"PRODUCTION")).thenReturn(task("RUNNING"));
        when(mapper.lock(TASK)).thenReturn(null);
        assertThatThrownBy(()->service.next(identity,"cloud-next-unit")).hasMessage("TEST_CLOUD_UNMANIFESTED_TASK_BLOCKED");
        verify(mapper,never()).cancelUnexecuted(anyLong(),anyLong(),anyString(),anyString(),any()); verifyNoInteractions(settlement);
    }
    private static final String OLD_TASK="CTA-LEGACY-UNEXECUTED";
    private static final String LEGACY_REASON="Verified old App task has no execution or settlement evidence";
    private static final String EVIDENCE="e".repeat(64);
    private Identity legacyFleet(boolean enabled) {
        Identity target=fleetIdentity();
        if(enabled) env.setProperty(PREFIX+"fleet-legacy-enabled","true");
        when(assignments.lockOwnedDevice(7L,12L)).thenReturn(new DeviceRow(12L,"NEX-FLEET-12","DEVICE","S1","S1",
                "ACTIVE","stellarbox-s1",NOW.minusDays(2),NOW.minusDays(2),96,"unit","ONLINE",null,false));
        when(mapper.liveFleetBinding(NOW.minusDays(1),null,7,12,"NEX-FLEET-12",false,enabled)).thenReturn(1);
        return target;
    }
    private AssignmentRow fleetTask(String taskNo,String model,String client) {
        return new AssignmentRow(taskNo,12L,"EM-UNIT",TASK_NAME,"EM",model,client,"RUNNING",new BigDecimal("0.045005"),
                5,0,NOW.minusSeconds(10),NOW.plusHours(24),null,null,NONCE,NOW.plusHours(24));
    }
    private void unmanifestedFleetTask() {
        when(assignments.lockActiveAssignment(7L,12L,"PRODUCTION")).thenReturn(fleetTask(OLD_TASK,"BGE-M3","UVEL App"));
        when(mapper.countActive(7,12)).thenReturn(1);
    }
    private void admitLegacyFleetTask() {
        when(mapper.legacyAdmission(eq(7L),eq(12L),eq("NEX-FLEET-12"),nullable(String.class),eq("EM-UNIT")))
                .thenReturn(new LegacyAdmission(OLD_TASK,LEGACY_REASON,EVIDENCE));
    }
    private Execution fleetExecution() {
        var original=execution(null,null,null);
        return new Execution(TASK,"fleet-12",7,12,"NEX-FLEET-12","EM-UNIT",original.nextKeyHash(),CLAIM,
                original.inputJson(),original.inputHash(),original.modelTag(),original.modelDigest(),NONCE,null,null,null,null,NOW);
    }
    @Test void legacyFleetIsOffByDefaultAndCannotUseTheSingleExecutorCancellationOverride() {
        Identity target=legacyFleet(false); unmanifestedFleetTask();
        env.setProperty(PREFIX+"cancel-unexecuted-task-no",OLD_TASK);
        assertThatThrownBy(()->service.next(target,"fleet-next-legacy")).hasMessage("TEST_CLOUD_UNMANIFESTED_TASK_BLOCKED");
        verify(mapper,never()).legacyAdmission(7,12,"NEX-FLEET-12",OLD_TASK,"EM-UNIT");
        verify(mapper,never()).cancelUnexecuted(anyLong(),anyLong(),anyString(),anyString(),any());
        verify(assignments,never()).clearRuntimeTask(anyLong(),anyLong(),anyString(),any()); verifyNoInteractions(settlement);
    }
    @Test void enabledLegacyFleetDiscoveryAndAuthenticationPassTheExplicitGateToTheMapper() {
        fleetIdentity(); env.setProperty(PREFIX+"fleet-legacy-enabled","true");
        var candidate=new TestCloudExecutorMapper.FleetCandidate(7,13,"NEX-LEGACY-13");
        when(mapper.fleetCandidates(NOW.minusDays(1),null,0,11,2,true)).thenReturn(List.of(candidate));
        when(mapper.fleetCandidate(NOW.minusDays(1),null,13,11,true)).thenReturn(candidate);
        var fleet=service.authenticate("Bearer "+TOKEN,"fleet");
        assertThat((List<?>)service.instances(fleet,0,1).getData().get("instances")).hasSize(1);
        assertThat(service.authenticate("Bearer "+TOKEN,"fleet-13").instanceNo()).isEqualTo("NEX-LEGACY-13");
        verifyNoInteractions(settlement); verify(mapper,never()).register(any(),any());
    }
    @Test void enabledLegacyFleetStillRequiresItsExactLockedAdmission() {
        Identity target=legacyFleet(true); unmanifestedFleetTask();
        assertThatThrownBy(()->service.next(target,"fleet-next-legacy")).hasMessage("TEST_CLOUD_BINDING_INVALID");
        verify(mapper).legacyAdmission(7,12,"NEX-FLEET-12",null,"EM-UNIT");
        verify(mapper).liveFleetBinding(NOW.minusDays(1),null,7,12,"NEX-FLEET-12",false,false);
        verify(mapper,never()).cancelUnexecuted(anyLong(),anyLong(),anyString(),anyString(),any());
        verify(assignments,never()).clearRuntimeTask(anyLong(),anyLong(),anyString(),any()); verifyNoInteractions(settlement);
    }
    @Test void aHistoricalAdmissionForAnotherConfigCannotRestoreDateExceptionsAfterItsOldTaskEnds() {
        Identity target=legacyFleet(true);
        when(assignments.lockActiveAssignment(7L,12L,"PRODUCTION")).thenReturn(null);
        when(mapper.countActive(7,12)).thenReturn(0);
        when(mapper.legacyAdmission(7,12,"NEX-FLEET-12",null,"EM-OLD"))
                .thenReturn(new LegacyAdmission(OLD_TASK,LEGACY_REASON,EVIDENCE));
        assertThatThrownBy(()->service.next(target,"fleet-next-config-drift")).hasMessage("TEST_CLOUD_BINDING_INVALID");
        verify(mapper).legacyAdmission(7,12,"NEX-FLEET-12",null,"EM-UNIT");
        verify(mapper).liveFleetBinding(NOW.minusDays(1),null,7,12,"NEX-FLEET-12",false,false);
        verify(mapper,never()).register(any(),any()); verify(mapper,never()).insert(any(Execution.class));
        verify(mapper,never()).heartbeat(anyLong(),any(),any(),any()); verifyNoInteractions(settlement);
    }
    @Test void aLockedCurrentConfigAdmissionStillCannotCancelADifferentOldTask() {
        Identity target=legacyFleet(true); unmanifestedFleetTask();
        when(mapper.legacyAdmission(7,12,"NEX-FLEET-12",null,"EM-UNIT"))
                .thenReturn(new LegacyAdmission("CTA-ANOTHER-OLD-TASK",LEGACY_REASON,EVIDENCE));
        assertThatThrownBy(()->service.next(target,"fleet-next-wrong-old-task")).hasMessage("TEST_CLOUD_UNMANIFESTED_TASK_BLOCKED");
        verify(mapper).legacyAdmission(7,12,"NEX-FLEET-12",OLD_TASK,"EM-UNIT");
        verify(mapper,never()).cancelUnexecuted(anyLong(),anyLong(),anyString(),anyString(),any());
        verify(assignments,never()).clearRuntimeTask(anyLong(),anyLong(),anyString(),any()); verifyNoInteractions(settlement);
    }
    @Test void legacyReplacementRejectsConflictingActiveTaskCountsBeforeAnyCancellationOrClaim() {
        Identity target=legacyFleet(true); unmanifestedFleetTask(); admitLegacyFleetTask();
        for(int activeCount:List.of(0,2)) {
            when(mapper.countActive(7,12)).thenReturn(activeCount);
            assertThatThrownBy(()->service.next(target,"fleet-next-legacy"))
                    .as("authoritative active count %s",activeCount).hasMessage("TEST_CLOUD_ACTIVE_TASK_CONFLICT");
        }
        verify(mapper,never()).cancelUnexecuted(anyLong(),anyLong(),anyString(),anyString(),any());
        verify(assignments,never()).clearRuntimeTask(anyLong(),anyLong(),anyString(),any()); verifyNoInteractions(settlement);
    }
    @Test void failedCancellationCasDoesNotClearTheRuntimeOrCreateAReplacement() {
        Identity target=legacyFleet(true); unmanifestedFleetTask(); admitLegacyFleetTask();
        assertThatThrownBy(()->service.next(target,"fleet-next-legacy")).hasMessage("TEST_CLOUD_UNMANIFESTED_TASK_BLOCKED");
        verify(mapper).cancelUnexecuted(7,12,OLD_TASK,"EM-UNIT",NOW);
        verify(assignments,never()).clearRuntimeTask(anyLong(),anyLong(),anyString(),any());
        verify(mapper,never()).insert(any(Execution.class)); verifyNoInteractions(settlement);
    }
    @Test void admittedUnexecutedLegacyTaskIsCancelledBeforeNormalClaimAndManifestWithoutDirectSettlement() {
        Identity target=legacyFleet(true); unmanifestedFleetTask(); admitLegacyFleetTask();
        when(mapper.cancelUnexecuted(7,12,OLD_TASK,"EM-UNIT",NOW)).thenReturn(1);
        when(mapper.heartbeat(eq(12L),nullable(String.class),eq("test-cloud:fleet-12"),eq(NOW))).thenReturn(1);
        var claimed=mock(AppTaskAssignmentView.class);
        when(claimed.taskNo()).thenReturn(TASK); when(claimed.taskId()).thenReturn("EM-UNIT"); when(claimed.model()).thenReturn("BGE-M3");
        when(settlement.claimForTestCloud(7L,12L)).thenReturn(ApiResult.ok(claimed));
        when(assignments.lockAssignment(7L,TASK,"PRODUCTION")).thenReturn(fleetTask(TASK,KIND,CLIENT));
        when(mapper.insert(any(Execution.class))).thenReturn(1);
        when(mapper.acknowledge(7,12,TASK,"EM-UNIT",NOW)).thenReturn(1);
        var data=service.next(target,"fleet-next-legacy").getData();
        assertThat(data.get("taskNo")).isEqualTo(TASK); assertThat(data.get("proofNonce")).isEqualTo(NONCE);
        var order=inOrder(assignments,mapper,settlement);
        order.verify(mapper).cancelUnexecuted(7,12,OLD_TASK,"EM-UNIT",NOW);
        order.verify(assignments).clearRuntimeTask(7L,12L,OLD_TASK,NOW);
        order.verify(settlement).claimForTestCloud(7L,12L);
        var manifest=org.mockito.ArgumentCaptor.forClass(Execution.class);
        order.verify(mapper).insert(manifest.capture());
        assertThat(manifest.getValue().taskNo()).isEqualTo(TASK).isNotEqualTo(OLD_TASK);
        assertThat(manifest.getValue().executorId()).isEqualTo("fleet-12");
        assertThat(manifest.getValue().ownerId()).isEqualTo(7); assertThat(manifest.getValue().deviceId()).isEqualTo(12);
        assertThat(manifest.getValue().instanceNo()).isEqualTo("NEX-FLEET-12");
        assertThat(manifest.getValue().inputJson()).isEqualTo(inputJson());
        assertThat(manifest.getValue().inputHash()).isEqualTo(TestCloudExecutorService.sha256(inputJson()));
        assertThat(manifest.getValue().modelDigest()).isEqualTo(DIGEST); assertThat(manifest.getValue().resultHash()).isNull();
        verify(audit).recordRequiredForTrustedActor(argThat(request->"TEST_CLOUD_UNEXECUTED_TASK_CANCELLED".equals(request.getAction())
                && request.getDetail() instanceof Map<?,?> detail && OLD_TASK.equals(detail.get("oldTaskNo"))
                && LEGACY_REASON.equals(detail.get("reason")) && EVIDENCE.equals(detail.get("evidenceSha256"))));
        verify(settlement,never()).completeForTestCloud(anyString(),any(),any()); verify(service,never()).infer(any());
    }
    @Test void anExistingManifestIsResumedWithoutLegacyCancellationOrAnotherClaim() {
        Identity target=legacyFleet(true); admitLegacyFleetTask();
        when(mapper.countActive(7,12)).thenReturn(1);
        var active=fleetTask(TASK,KIND,CLIENT);
        when(assignments.lockActiveAssignment(7L,12L,"PRODUCTION")).thenReturn(active);
        when(mapper.lock(TASK)).thenReturn(fleetExecution());
        assertThat(service.next(target,"fleet-next-existing").getData().get("taskNo")).isEqualTo(TASK);
        verify(mapper,never()).legacyAdmission(7,12,"NEX-FLEET-12",TASK,"EM-UNIT");
        verify(mapper,never()).cancelUnexecuted(anyLong(),anyLong(),anyString(),anyString(),any()); verifyNoInteractions(settlement);
    }
    @Test void anExistingManifestCannotBypassASecondActiveTaskAtAnyWriteEntryPoint() {
        Identity target=legacyFleet(true);
        var active=fleetTask(TASK,KIND,CLIENT);
        when(assignments.lockActiveAssignment(7L,12L,"PRODUCTION")).thenReturn(active);
        when(assignments.lockAssignment(7L,TASK,"PRODUCTION")).thenReturn(active);
        when(mapper.lock(TASK)).thenReturn(fleetExecution()); when(mapper.countActive(7,12)).thenReturn(2);
        assertThatThrownBy(()->service.next(target,"fleet-next-existing")).hasMessage("TEST_CLOUD_ACTIVE_TASK_CONFLICT");
        assertThatThrownBy(()->service.heartbeat(target,TASK,CLAIM)).hasMessage("TEST_CLOUD_ACTIVE_TASK_CONFLICT");
        assertThatThrownBy(()->service.complete(target,TASK,"fleet-complete-unit",null)).hasMessage("TEST_CLOUD_ACTIVE_TASK_CONFLICT");
        verify(mapper,never()).heartbeat(anyLong(),any(),any(),any());
        verify(mapper,never()).saveResult(anyString(),anyString(),any(),anyString(),anyString());
        verify(mapper,never()).cancelUnexecuted(anyLong(),anyLong(),anyString(),anyString(),any()); verifyNoInteractions(settlement);
    }
    @Test void aRegisteredLegacyExecutorCanReconcileButCannotWriteAfterItsLegacyGateIsRevoked() {
        Identity target=legacyFleet(true); env.setProperty(PREFIX+"fleet-legacy-enabled","false");
        when(mapper.countActive(7,12)).thenReturn(1);
        var active=fleetTask(TASK,KIND,CLIENT);
        when(assignments.lockActiveAssignment(7L,12L,"PRODUCTION")).thenReturn(active);
        when(assignments.lockAssignment(7L,TASK,"PRODUCTION")).thenReturn(active);
        when(mapper.lock(TASK)).thenReturn(fleetExecution());
        when(mapper.historicalReceipt(TASK,7,12)).thenReturn(new TestCloudExecutorMapper.HistoricalReceipt(
                "RUNNING","EM-UNIT",KIND,CLIENT,null,null,null));
        assertThat(service.receipt(target,TASK).getData().get("found")).isEqualTo(false);
        assertThatThrownBy(()->service.next(target,"fleet-next-revoked")).hasMessage("TEST_CLOUD_BINDING_INVALID");
        assertThatThrownBy(()->service.heartbeat(target,TASK,CLAIM)).hasMessage("TEST_CLOUD_BINDING_INVALID");
        assertThatThrownBy(()->service.complete(target,TASK,"fleet-complete-unit",null)).hasMessage("TEST_CLOUD_BINDING_INVALID");
        verify(mapper,never()).heartbeat(anyLong(),any(),any(),any());
        verify(mapper,never()).saveResult(anyString(),anyString(),any(),anyString(),anyString());
        verify(mapper,never()).cancelUnexecuted(anyLong(),anyLong(),anyString(),anyString(),any()); verifyNoInteractions(settlement);
    }
    @Test void theExplicitSingleExecutorCancellationAuthorizationStillUsesItsOriginalPath() {
        env.setProperty(PREFIX+"cancel-unexecuted-task-no",TASK);
        when(assignments.lockActiveAssignment(7L,11L,"PRODUCTION")).thenReturn(task("RUNNING"));
        when(mapper.lock(TASK)).thenReturn(null);
        when(mapper.countActive(7,11)).thenReturn(1);
        when(mapper.cancelUnexecuted(7,11,TASK,"EM-UNIT",NOW)).thenReturn(1);
        when(assignments.lockDeviceTaskLock(7L,11L,"PRODUCTION")).thenReturn(new DeviceLockRow(NOW.plusMinutes(1),TASK));
        assertThat(service.next(identity,"cloud-next-single").getData().get("idle")).isEqualTo(true);
        verify(assignments).clearRuntimeTask(7L,11L,TASK,NOW);
        verify(mapper,never()).legacyAdmission(anyLong(),anyLong(),anyString(),nullable(String.class),anyString());
        verifyNoInteractions(settlement);
    }
    @Test void correctHashWithWrongSemanticOutputCannotReachSettlement() throws Exception {
        doReturn(TestCloudBgeArtifactTest.expected(0.25)).when(service).infer(identity);
        assertThatThrownBy(()->service.complete(identity,TASK,"cloud-complete-unit",request(0.5f))).hasMessage("TEST_CLOUD_RESULT_INVALID");
        verifyNoInteractions(settlement); verify(mapper,never()).saveResult(anyString(),anyString(),any(),anyString(),anyString());
    }
    @Test void foreignNonceInputModelClaimAndOverflowTimestampFailBeforeInference() throws Exception {
        CompleteRequest valid=request(0.25f);
        for(CompleteRequest wrong:List.of(
                new CompleteRequest(SPEC,"f".repeat(64),DIGEST,valid.resultArtifactBase64(),valid.resultHash(),NONCE,valid.proofTimestamp(),CLAIM),
                new CompleteRequest(SPEC,valid.inputHash(),DIGEST,valid.resultArtifactBase64(),valid.resultHash(),"f".repeat(64),valid.proofTimestamp(),CLAIM),
                new CompleteRequest(SPEC,valid.inputHash(),"sha256:"+"f".repeat(64),valid.resultArtifactBase64(),valid.resultHash(),NONCE,valid.proofTimestamp(),CLAIM),
                new CompleteRequest(SPEC,valid.inputHash(),DIGEST,valid.resultArtifactBase64(),valid.resultHash(),NONCE,Long.MIN_VALUE,CLAIM),
                new CompleteRequest(SPEC,valid.inputHash(),DIGEST,valid.resultArtifactBase64(),valid.resultHash(),NONCE,valid.proofTimestamp(),"f".repeat(64)))) {
            assertThatThrownBy(()->service.complete(identity,TASK,"cloud-complete-unit",wrong)).hasMessage("TEST_CLOUD_PROOF_INVALID");
        }
        verify(service,never()).infer(any()); verifyNoInteractions(settlement);
    }
    @Test void verifiedResultUsesNormalSettlementWithBoundDomainProof() throws Exception {
        doReturn(TestCloudBgeArtifactTest.expected(0.25)).when(service).infer(identity);
        when(mapper.saveResult(eq(TASK),anyString(),any(),anyString(),anyString())).thenReturn(1);
        service.complete(identity,TASK,"cloud-complete-unit",request(0.25f));
        var proof=org.mockito.ArgumentCaptor.forClass(CloudProof.class);
        verify(settlement).completeForTestCloud(eq(TASK),any(),proof.capture());
        assertThat(proof.getValue().ownerId()).isEqualTo(7); assertThat(proof.getValue().deviceId()).isEqualTo(11);
        assertThat(proof.getValue().modelDigest()).isEqualTo(DIGEST);
        assertThat(proof.getValue().inputHash()).isEqualTo(TestCloudExecutorService.sha256(inputJson()));
        assertThat(proof.getValue().proofHash()).hasSize(64).isNotEqualTo(proof.getValue().resultHash());
    }
    @Test void sameKeySameBodyReplayDoesNotExecuteOrSettleAgainAndDifferentKeyIsRejected() throws Exception {
        CompleteRequest request=request(0.25f);
        String body=new com.fasterxml.jackson.databind.ObjectMapper().writeValueAsString(request);
        when(mapper.lock(TASK)).thenReturn(execution(request.resultHash(),TestCloudExecutorService.sha256("cloud-complete-unit"),TestCloudExecutorService.sha256(body)));
        when(assignments.lockAssignment(7L,TASK,"PRODUCTION")).thenReturn(task("COMPLETED"));
        service.complete(identity,TASK,"cloud-complete-unit",request);
        verify(service,never()).infer(any()); verifyNoInteractions(settlement);
        assertThatThrownBy(()->service.complete(identity,TASK,"cloud-complete-other",request)).hasMessage("TEST_CLOUD_COMPLETION_REPLAYED");
    }
    @Test void staleRuntimeAndExpiredLeaseNeverRecomputeOrCredit() throws Exception {
        when(assignments.lockTestWorkerRuntime(7L,11L,"NEX-UNIT-CLOUD")).thenReturn(new TestWorkerRuntimeRow("ONLINE",TASK,CLIENT,"test-cloud:cloud-unit",NOW.minusSeconds(121),null));
        assertThatThrownBy(()->service.complete(identity,TASK,"cloud-complete-unit",request(0.25f))).hasMessage("TEST_CLOUD_HEARTBEAT_STALE");
        verify(service,never()).infer(any()); verifyNoInteractions(settlement);
    }
    @Test void receiptReconciliationDoesNotClaimCancelOrComplete() {
        var result=service.receipt(identity,TASK).getData(); assertThat(result.get("found")).isEqualTo(false);
        assertThat(result.get("modelDigest")).isEqualTo(DIGEST); assertThat(result.get("inputHash")).isEqualTo(TestCloudExecutorService.sha256(inputJson()));
        verifyNoInteractions(settlement); verify(mapper,never()).cancelUnexecuted(anyLong(),anyLong(),anyString(),anyString(),any());
        verify(mapper,never()).heartbeat(anyLong(),any(),any(),any());
    }
    @Test void modelResponseSlowBodyHasATotalDeadlineAndCannotHoldTheTransactionIndefinitely() throws Exception {
        var server=com.sun.net.httpserver.HttpServer.create(new java.net.InetSocketAddress("127.0.0.1",0),0);
        var release=new java.util.concurrent.CountDownLatch(1);
        server.createContext("/api/embed",exchange->{
            exchange.sendResponseHeaders(200,4);
            try { release.await(); exchange.getResponseBody().write("{}  ".getBytes(java.nio.charset.StandardCharsets.UTF_8)); }
            catch(InterruptedException interrupted) { Thread.currentThread().interrupt(); }
            finally { exchange.close(); }
        });
        server.start();
        try {
            env.setProperty(PREFIX+"model-timeout-ms","100");
            long start=System.nanoTime();
            assertThatThrownBy(()->org.springframework.test.util.ReflectionTestUtils.invokeMethod(service,"http",
                    java.net.URI.create("http://127.0.0.1:"+server.getAddress().getPort()+"/api/embed"),"{}"))
                    .hasMessage("TEST_CLOUD_MODEL_UNAVAILABLE");
            assertThat(java.time.Duration.ofNanos(System.nanoTime()-start)).isLessThan(java.time.Duration.ofSeconds(2));
        } finally { release.countDown(); server.stop(0); }
    }
    @Test void modelDigestDriftAfterRealInferenceResponseIsRejected() throws Exception {
        var server=com.sun.net.httpserver.HttpServer.create(new java.net.InetSocketAddress("127.0.0.1",0),0);
        var tagCalls=new java.util.concurrent.atomic.AtomicInteger();
        server.createContext("/api/tags",exchange->{
            String digest=tagCalls.incrementAndGet()==1?DIGEST:"sha256:"+"f".repeat(64);
            byte[] body=("{\"models\":[{\"name\":\"bge-m3:567m\",\"digest\":\""+digest+"\"}]}").getBytes(java.nio.charset.StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200,body.length); exchange.getResponseBody().write(body); exchange.close();
        });
        server.createContext("/api/embed",exchange->{
            byte[] body=new com.fasterxml.jackson.databind.ObjectMapper().writeValueAsBytes(Map.of("model","bge-m3:567m","embeddings",TestCloudBgeArtifactTest.expected(0.25)));
            exchange.sendResponseHeaders(200,body.length); exchange.getResponseBody().write(body); exchange.close();
        });
        server.start();
        try {
            env.setProperty(PREFIX+"ollama-origin","http://127.0.0.1:"+server.getAddress().getPort());
            assertThatThrownBy(()->service.infer(identity)).hasMessage("TEST_CLOUD_MODEL_DIGEST_MISMATCH");
            assertThat(tagCalls).hasValue(2);
        } finally { server.stop(0); }
    }
}
