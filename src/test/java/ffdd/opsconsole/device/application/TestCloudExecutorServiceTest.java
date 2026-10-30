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
        when(assignments.ownedDevice(7L,11L)).thenReturn(device());
        when(assignments.paidCloudShareDailyNex(7L,11L)).thenReturn(new BigDecimal("3"));
        when(assignments.assignment(7L,TASK,"PRODUCTION")).thenReturn(task("RUNNING"));
        when(mapper.execution(TASK)).thenReturn(execution(null,null,null));
        when(assignments.testWorkerRuntime(7L,11L,"NEX-UNIT-CLOUD")).thenReturn(new TestWorkerRuntimeRow("ONLINE",TASK,CLIENT,"test-cloud:cloud-unit",NOW,null));
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
    @Test void singleEligibilityReportsLifecycleWithoutHeartbeatClaimOrSettlement() {
        when(mapper.lockActiveTaskNos(7,11)).thenReturn(List.of());
        assertThat(service.eligibility(identity).getData()).containsEntry("eligible",true).containsEntry("activeTaskNo",null);
        when(assignments.lockOwnedDevice(7L,11L)).thenReturn(new DeviceRow(11L,"NEX-UNIT-CLOUD","SHARE","SHARE","Cloud",
                "DEACTIVATED","cloud-share",NOW.minusDays(1),NOW.minusHours(1),24,"unit","OFFLINE","USER_DEACTIVATED",false));
        assertThat(service.eligibility(identity).getData()).containsEntry("eligible",false).containsEntry("activeTaskNo",null);
        when(assignments.lockOwnedDevice(7L,11L)).thenReturn(device());
        when(mapper.lockActiveTaskNos(7,11)).thenReturn(List.of(TASK));
        assertThat(service.eligibility(identity).getData()).containsEntry("eligible",true).containsEntry("activeTaskNo",TASK);
        verify(mapper,never()).heartbeat(anyLong(),any(),any(),any());
        verify(mapper,never()).register(any(),any()); verify(mapper,never()).insert(any(Execution.class));
        verify(mapper,never()).cancelUnexecuted(anyLong(),anyLong(),anyString(),anyString(),any());
        verifyNoInteractions(settlement,audit);
    }
    @Test void singleEligibilityNeverAcceptsForeignIdentityOrMultipleLiveTasks() {
        when(mapper.lockActiveTaskNos(7,11)).thenReturn(List.of(TASK,"CTA-OTHER"));
        assertThatThrownBy(()->service.eligibility(identity)).hasMessage("TEST_CLOUD_ACTIVE_TASK_CONFLICT");
        when(assignments.lockOwnedDevice(7L,11L)).thenReturn(null);
        assertThatThrownBy(()->service.eligibility(identity)).hasMessage("TEST_CLOUD_BINDING_INVALID");
        verifyNoInteractions(settlement,audit);
    }
    private Identity fleetIdentity() {
        env.setProperty(PREFIX+"fleet-enabled","true"); env.setProperty(PREFIX+"fleet-started-at",NOW.minusDays(1).toString());
        var b=new TestCloudExecutorMapper.Binding("fleet-12",7,12,"NEX-FLEET-12","EM-UNIT","bge-m3:567m",DIGEST);
        when(mapper.binding("fleet-12")).thenReturn(b);
        when(mapper.lockBinding("fleet-12")).thenReturn(b);
        when(assignments.ownedDevice(7L,12L)).thenReturn(new DeviceRow(12L,"NEX-FLEET-12","DEVICE","S1","S1",
                "ACTIVE","stellarbox-s1",NOW.minusDays(1),NOW.minusHours(1),96,"unit","ONLINE",null,false));
        when(assignments.assignment(7L,TASK,"PRODUCTION")).thenReturn(fleetTask(TASK,KIND,CLIENT));
        when(mapper.execution(TASK)).thenReturn(fleetExecution());
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
        verify(mapper).fleetBinding(NOW.minusDays(1),tradeinCutoff,7,12,"NEX-FLEET-12",true,false);
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
    @Test void unreviewedExistingDeviceLockStillStopsCloudClaimWithoutHeartbeatOrSettlement() {
        when(assignments.lockDeviceTaskLock(7L,11L,"PRODUCTION"))
                .thenReturn(new DeviceLockRow(NOW.plusMinutes(1),"UNREVIEWED-SOURCE"));
        assertThat(service.next(identity,"cloud-next-locked").getData().get("idle")).isEqualTo(true);
        verify(mapper,never()).heartbeat(anyLong(),any(),any(),any());
        verify(mapper,never()).insert(any(Execution.class)); verifyNoInteractions(settlement);
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
        when(mapper.activeTaskNos(7,12)).thenReturn(List.of(TASK,"CTA-OTHER"));
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
    @Test void slowValidationHoldsNoOwnerDeviceTaskOrRuntimeLockAndAllowsHeartbeat() throws Exception {
        var entered=new java.util.concurrent.CountDownLatch(1);
        var release=new java.util.concurrent.CountDownLatch(1);
        var worker=java.util.concurrent.Executors.newSingleThreadExecutor();
        doAnswer(call->{ entered.countDown(); assertThat(release.await(10,java.util.concurrent.TimeUnit.SECONDS)).isTrue();
            return TestCloudBgeArtifactTest.expected(0.25); }).when(service).infer(identity);
        when(mapper.saveResult(eq(TASK),anyString(),any(),anyString(),anyString())).thenReturn(1);
        CompleteRequest request=request(0.25f);
        var completion=worker.submit(()->service.complete(identity,TASK,"cloud-complete-unit",request));
        try {
            assertThat(entered.await(10,java.util.concurrent.TimeUnit.SECONDS)).isTrue();
            verify(assignments,never()).lockProductionUser(anyLong());
            verify(assignments,never()).lockOwnedDevice(anyLong(),anyLong());
            verify(assignments,never()).lockAssignment(anyLong(),anyString(),anyString());
            verify(assignments,never()).lockTestWorkerRuntime(anyLong(),anyLong(),anyString());
            verify(assignments,never()).lockPaidCloudShareDailyNex(anyLong(),anyLong());
            verify(mapper,never()).lock(anyString()); verify(mapper,never()).lockBinding(anyString());
            verify(mapper,never()).liveFleetBinding(any(),any(),anyLong(),anyLong(),anyString(),anyBoolean(),anyBoolean());
            when(mapper.heartbeat(11,null,"test-cloud:cloud-unit",NOW)).thenReturn(1);
            assertThat(service.heartbeat(identity,null,null).getData()).containsEntry("activeTaskNo",null);
            assertThat(completion.isDone()).isFalse();
            release.countDown(); completion.get(10,java.util.concurrent.TimeUnit.SECONDS);
            verify(settlement).completeForTestCloud(eq(TASK),any(),any());
        } finally { release.countDown(); worker.shutdownNow(); }
    }
    @Test void finalCurrentReadsRejectDeactivationPauseNonceLeaseAndRuntimeChangesDuringInference() throws Exception {
        Map<String,java.util.function.Consumer<TestCloudExecutorServiceTest>> revocations=new LinkedHashMap<>();
        revocations.put("TEST_CLOUD_BINDING_INVALID",fixture->when(fixture.assignments.lockOwnedDevice(7L,11L)).thenReturn(
                new DeviceRow(11L,"NEX-UNIT-CLOUD","SHARE","SHARE","Cloud","INACTIVE","cloud-share",NOW.minusDays(1),null,24,"unit","OFFLINE",null,false)));
        revocations.put("TEST_CLOUD_BINDING_INVALID:pause",fixture->when(fixture.assignments.lockOwnedDevice(7L,11L)).thenReturn(
                new DeviceRow(11L,"NEX-UNIT-CLOUD","SHARE","SHARE","Cloud","ACTIVE","cloud-share",NOW.minusDays(1),NOW.minusHours(1),24,"unit","ONLINE",null,true)));
        revocations.put("TEST_CLOUD_TASK_INVALID:nonce",fixture->when(fixture.assignments.lockAssignment(7L,TASK,"PRODUCTION")).thenReturn(
                new AssignmentRow(TASK,11L,"EM-UNIT",TASK_NAME,"EM",KIND,CLIENT,"RUNNING",BigDecimal.ONE,5,0,
                        NOW.minusSeconds(10),NOW.plusHours(24),null,null,"f".repeat(64),NOW.plusHours(24))));
        revocations.put("TEST_CLOUD_TASK_INVALID:lease",fixture->when(fixture.assignments.lockAssignment(7L,TASK,"PRODUCTION")).thenReturn(
                new AssignmentRow(TASK,11L,"EM-UNIT",TASK_NAME,"EM",KIND,CLIENT,"RUNNING",BigDecimal.ONE,5,0,
                        NOW.minusSeconds(10),NOW,null,null,NONCE,NOW.plusHours(24))));
        revocations.put("TEST_CLOUD_HEARTBEAT_STALE",fixture->when(fixture.assignments.lockTestWorkerRuntime(7L,11L,"NEX-UNIT-CLOUD")).thenReturn(
                new TestWorkerRuntimeRow("ONLINE","CTA-OTHER",CLIENT,"test-cloud:cloud-unit",NOW,null)));
        revocations.put("TEST_CLOUD_PROOF_INVALID:claimToken",fixture->{
            var e=execution(null,null,null);
            when(fixture.mapper.lock(TASK)).thenReturn(new Execution(e.taskNo(),e.executorId(),e.ownerId(),e.deviceId(),e.instanceNo(),
                    e.taskConfigId(),e.nextKeyHash(),"f".repeat(64),e.inputJson(),e.inputHash(),e.modelTag(),e.modelDigest(),e.proofNonce(),
                    null,null,null,null,e.createdAt()));
        });
        for(var revocation:revocations.entrySet()) {
            var fixture=new TestCloudExecutorServiceTest(); fixture.setup();
            doAnswer(call->{ revocation.getValue().accept(fixture); return TestCloudBgeArtifactTest.expected(0.25); })
                    .when(fixture.service).infer(fixture.identity);
            var request=fixture.request(0.25f);
            assertThatThrownBy(()->fixture.service.complete(fixture.identity,TASK,"cloud-complete-unit",request))
                    .as(revocation.getKey()).hasMessage(revocation.getKey().split(":")[0]);
            verify(fixture.mapper,never()).saveResult(anyString(),anyString(),any(),anyString(),anyString());
            verifyNoInteractions(fixture.settlement);
        }
    }
    private Identity completableFleet() {
        Identity target=fleetIdentity();
        when(assignments.lockOwnedDevice(7L,12L)).thenReturn(new DeviceRow(12L,"NEX-FLEET-12","DEVICE","S1","S1",
                "ACTIVE","stellarbox-s1",NOW.minusDays(1),NOW.minusHours(1),96,"unit","ONLINE",null,false));
        when(assignments.lockAssignment(7L,TASK,"PRODUCTION")).thenReturn(fleetTask(TASK,KIND,CLIENT));
        when(mapper.lock(TASK)).thenReturn(fleetExecution());
        when(mapper.countActive(7,12)).thenReturn(1); when(mapper.activeTaskNos(7,12)).thenReturn(List.of(TASK));
        when(mapper.fleetBinding(NOW.minusDays(1),null,7,12,"NEX-FLEET-12",true,false)).thenReturn(1);
        when(mapper.liveFleetBinding(NOW.minusDays(1),null,7,12,"NEX-FLEET-12",true,false)).thenReturn(1);
        var runtime=new TestWorkerRuntimeRow("ONLINE",TASK,CLIENT,"test-cloud:fleet-12",NOW,null);
        when(assignments.testWorkerRuntime(7L,12L,"NEX-FLEET-12")).thenReturn(runtime);
        when(assignments.lockTestWorkerRuntime(7L,12L,"NEX-FLEET-12")).thenReturn(runtime);
        return target;
    }
    @Test void deletedOrChangedRegisteredBindingDuringInferenceCannotUseCachedPrecheckAuthority() throws Exception {
        for(boolean deleted:List.of(true,false)) {
            var fixture=new TestCloudExecutorServiceTest(); fixture.setup(); Identity target=fixture.completableFleet();
            doAnswer(call->{
                when(fixture.mapper.lockBinding("fleet-12")).thenReturn(deleted?null:new TestCloudExecutorMapper.Binding(
                        "fleet-12",8,12,"NEX-FLEET-12","EM-UNIT","bge-m3:567m",DIGEST));
                return TestCloudBgeArtifactTest.expected(0.25);
            }).when(fixture.service).infer(target);
            CompleteRequest request=fixture.request(0.25f);
            assertThatThrownBy(()->fixture.service.complete(target,TASK,"fleet-complete-unit",request)).hasMessage("TEST_CLOUD_AUTH_INVALID");
            verify(fixture.mapper,never()).saveResult(anyString(),anyString(),any(),anyString(),anyString());
            verifyNoInteractions(fixture.settlement);
        }
    }
    private void completedResult(CompleteRequest request,String key) throws Exception {
        String body=new com.fasterxml.jackson.databind.ObjectMapper().writeValueAsString(request);
        var e=execution(request.resultHash(),sha256(key),sha256(body));
        when(mapper.lock(TASK)).thenReturn(new Execution(e.taskNo(),e.executorId(),e.ownerId(),e.deviceId(),e.instanceNo(),
                e.taskConfigId(),e.nextKeyHash(),e.claimToken(),e.inputJson(),e.inputHash(),e.modelTag(),e.modelDigest(),e.proofNonce(),
                e.resultHash(),Base64.getDecoder().decode(request.resultArtifactBase64()),e.completeKeyHash(),e.completeBodyHash(),e.createdAt()));
        when(assignments.lockAssignment(7L,TASK,"PRODUCTION")).thenReturn(task("COMPLETED"));
        String proof=sha256(String.join("\n",SPEC,identity.executorId(),"7","11",identity.instanceNo(),TASK,"EM-UNIT",NONCE,
                request.inputHash(),"bge-m3:567m",DIGEST,request.resultHash()));
        when(assignments.receipt(7L,"CTR-"+TASK)).thenReturn(new ReceiptRow("CTR-"+TASK,TASK,11L,identity.instanceNo(),
                "Cloud","SHARE",null,24,"EM-UNIT",TASK_NAME,"EM",KIND,CLIENT,BigDecimal.ONE,BigDecimal.ONE,
                "CREDITED",proof,NOW.minusSeconds(10),NOW,10));
    }
    @Test void competingSameRequestCompletionReturnsCurrentReceiptWithoutSecondWriteOrSettlement() throws Exception {
        CompleteRequest request=request(0.25f);
        doAnswer(call->{ completedResult(request,"cloud-complete-unit"); return TestCloudBgeArtifactTest.expected(0.25); })
                .when(service).infer(identity);
        assertThat(service.complete(identity,TASK,"cloud-complete-unit",request).getData())
                .containsEntry("found",true).containsEntry("status","COMPLETED");
        verify(mapper,never()).saveResult(anyString(),anyString(),any(),anyString(),anyString()); verifyNoInteractions(settlement);
    }
    @Test void competingDifferentCompletionKeyIsRejectedAfterInferenceWithoutSecondWrite() throws Exception {
        CompleteRequest request=request(0.25f);
        doAnswer(call->{ completedResult(request,"cloud-complete-other"); return TestCloudBgeArtifactTest.expected(0.25); })
                .when(service).infer(identity);
        assertThatThrownBy(()->service.complete(identity,TASK,"cloud-complete-unit",request)).hasMessage("TEST_CLOUD_COMPLETION_REPLAYED");
        verify(mapper,never()).saveResult(anyString(),anyString(),any(),anyString(),anyString()); verifyNoInteractions(settlement);
    }
    @Test void splitPrecheckReplayStillReloadsTaskAndReceiptAndDoesNotInfer() throws Exception {
        CompleteRequest request=request(0.25f); completedResult(request,"cloud-complete-unit");
        var e=execution(request.resultHash(),sha256("cloud-complete-unit"),sha256(new com.fasterxml.jackson.databind.ObjectMapper().writeValueAsString(request)));
        when(mapper.execution(TASK)).thenReturn(e);
        assertThat(service.complete(identity,TASK,"cloud-complete-unit",request).getData())
                .containsEntry("found",true).containsEntry("status","COMPLETED");
        verify(service,never()).infer(any()); verifyNoInteractions(settlement);
    }
    @Test void sameKeySameBodyReplayDoesNotExecuteOrSettleAgainAndDifferentKeyIsRejected() throws Exception {
        CompleteRequest request=request(0.25f);
        String body=new com.fasterxml.jackson.databind.ObjectMapper().writeValueAsString(request);
        when(mapper.lock(TASK)).thenReturn(execution(request.resultHash(),TestCloudExecutorService.sha256("cloud-complete-unit"),TestCloudExecutorService.sha256(body)));
        when(assignments.lockAssignment(7L,TASK,"PRODUCTION")).thenReturn(task("COMPLETED"));
        when(mapper.execution(TASK)).thenReturn(execution(request.resultHash(),TestCloudExecutorService.sha256("cloud-complete-unit"),TestCloudExecutorService.sha256(body)));
        when(assignments.assignment(7L,TASK,"PRODUCTION")).thenReturn(task("COMPLETED"));
        service.complete(identity,TASK,"cloud-complete-unit",request);
        verify(service,never()).infer(any()); verifyNoInteractions(settlement);
        assertThatThrownBy(()->service.complete(identity,TASK,"cloud-complete-other",request)).hasMessage("TEST_CLOUD_COMPLETION_REPLAYED");
    }
    @Test void staleRuntimeAndExpiredLeaseNeverRecomputeOrCredit() throws Exception {
        when(assignments.lockTestWorkerRuntime(7L,11L,"NEX-UNIT-CLOUD")).thenReturn(new TestWorkerRuntimeRow("ONLINE",TASK,CLIENT,"test-cloud:cloud-unit",NOW.minusSeconds(121),null));
        when(assignments.testWorkerRuntime(7L,11L,"NEX-UNIT-CLOUD")).thenReturn(new TestWorkerRuntimeRow("ONLINE",TASK,CLIENT,"test-cloud:cloud-unit",NOW.minusSeconds(121),null));
        assertThatThrownBy(()->service.complete(identity,TASK,"cloud-complete-unit",request(0.25f))).hasMessage("TEST_CLOUD_HEARTBEAT_STALE");
        when(assignments.assignment(7L,TASK,"PRODUCTION")).thenReturn(new AssignmentRow(TASK,11L,"EM-UNIT",TASK_NAME,"EM",KIND,CLIENT,
                "RUNNING",BigDecimal.ONE,5,0,NOW.minusSeconds(10),NOW,null,null,NONCE,NOW.plusHours(24)));
        assertThatThrownBy(()->service.complete(identity,TASK,"cloud-complete-unit",request(0.25f))).hasMessage("TEST_CLOUD_TASK_INVALID");
        verify(service,never()).infer(any()); verifyNoInteractions(settlement);
    }
    @Test void proofExpiringDuringInferenceIsRejectedBeforeAnyResultOrSettlementWrite() throws Exception {
        var current=new java.util.concurrent.atomic.AtomicReference<>(INSTANT);
        Clock advancing=new Clock() {
            public ZoneId getZone() { return ZoneOffset.UTC; }
            public Clock withZone(ZoneId zone) { return this; }
            public Instant instant() { return current.get(); }
        };
        var advancingService=spy(new TestCloudExecutorService(assignments,mapper,settlement,audit,advancing,env));
        doAnswer(call->{ current.set(INSTANT.plusSeconds(121)); return TestCloudBgeArtifactTest.expected(0.25); })
                .when(advancingService).infer(identity);
        assertThatThrownBy(()->advancingService.complete(identity,TASK,"cloud-complete-unit",request(0.25f)))
                .hasMessage("TEST_CLOUD_PROOF_INVALID");
        verify(mapper,never()).saveResult(anyString(),anyString(),any(),anyString(),anyString()); verifyNoInteractions(settlement);
    }
    @Test void fractionalSecondHeartbeatIsTruncatedBeforeDatetimeStorageAndCanCompleteNormally() throws Exception {
        Instant fractional=INSTANT.plusMillis(600);
        var fractionalService=spy(new TestCloudExecutorService(assignments,mapper,settlement,audit,Clock.fixed(fractional,ZoneOffset.UTC),env));
        when(mapper.heartbeat(11,null,"test-cloud:cloud-unit",NOW)).thenReturn(1);
        fractionalService.heartbeat(identity,null,null);
        verify(mapper).ensureRuntime(11,"test-cloud:cloud-unit",NOW);
        verify(mapper).heartbeat(11,null,"test-cloud:cloud-unit",NOW);
        assertThat(NOW.plusSeconds(1)).isAfter(LocalDateTime.ofInstant(fractional,ZoneOffset.UTC));
        when(assignments.lockTestWorkerRuntime(7L,11L,"NEX-UNIT-CLOUD"))
                .thenReturn(new TestWorkerRuntimeRow("ONLINE",TASK,CLIENT,"test-cloud:cloud-unit",NOW,null));
        doReturn(TestCloudBgeArtifactTest.expected(0.25)).when(fractionalService).infer(identity);
        when(mapper.saveResult(eq(TASK),anyString(),any(),anyString(),anyString())).thenReturn(1);
        fractionalService.complete(identity,TASK,"cloud-complete-fractional",request(0.25f));
        verify(settlement).completeForTestCloud(eq(TASK),any(),any());
    }
    @Test void aRoundedFutureHeartbeatIsStillRejectedWithoutRelaxingTheFutureTimeGate() throws Exception {
        var fractionalService=spy(new TestCloudExecutorService(assignments,mapper,settlement,audit,Clock.fixed(INSTANT.plusMillis(600),ZoneOffset.UTC),env));
        when(assignments.lockTestWorkerRuntime(7L,11L,"NEX-UNIT-CLOUD"))
                .thenReturn(new TestWorkerRuntimeRow("ONLINE",TASK,CLIENT,"test-cloud:cloud-unit",NOW.plusSeconds(1),null));
        when(assignments.testWorkerRuntime(7L,11L,"NEX-UNIT-CLOUD"))
                .thenReturn(new TestWorkerRuntimeRow("ONLINE",TASK,CLIENT,"test-cloud:cloud-unit",NOW.plusSeconds(1),null));
        assertThatThrownBy(()->fractionalService.complete(identity,TASK,"cloud-complete-future",request(0.25f)))
                .hasMessage("TEST_CLOUD_HEARTBEAT_STALE");
        verify(fractionalService,never()).infer(any()); verifyNoInteractions(settlement);
    }
    @Test void receiptReconciliationDoesNotClaimCancelOrComplete() {
        var result=service.receipt(identity,TASK).getData(); assertThat(result.get("found")).isEqualTo(false);
        assertThat(result.get("modelDigest")).isEqualTo(DIGEST); assertThat(result.get("inputHash")).isEqualTo(TestCloudExecutorService.sha256(inputJson()));
        verifyNoInteractions(settlement); verify(mapper,never()).cancelUnexecuted(anyLong(),anyLong(),anyString(),anyString(),any());
        verify(mapper,never()).heartbeat(anyLong(),any(),any(),any());
    }
    static final String RESUME_KEY="BGE-NEXT-"+"d".repeat(32);
    private Execution resumableExecution(String result,String completeKey,String completeBody) {
        var e=execution(result,completeKey,completeBody);
        return new Execution(e.taskNo(),e.executorId(),e.ownerId(),e.deviceId(),e.instanceNo(),e.taskConfigId(),sha256(RESUME_KEY),
                e.claimToken(),e.inputJson(),e.inputHash(),e.modelTag(),e.modelDigest(),e.proofNonce(),e.resultHash(),e.resultArtifact(),
                e.completeKeyHash(),e.completeBodyHash(),e.createdAt());
    }
    private void resumableTask() {
        when(mapper.lock(TASK)).thenReturn(resumableExecution(null,null,null));
        when(assignments.lockActiveAssignment(7L,11L,"PRODUCTION")).thenReturn(task("RUNNING"));
        when(mapper.countActive(7,11)).thenReturn(1);
    }
    @Test void originalManifestReadAuthorizesOnlyTheSameLiveUnsubmittedTaskAndHasNoWrites() {
        resumableTask();
        var data=service.manifest(identity,TASK,RESUME_KEY).getData();
        assertThat(data).containsEntry("resumeAllowed",true).containsEntry("nextKeyHash",sha256(RESUME_KEY))
                .containsEntry("taskNo",TASK).containsEntry("inputHash",sha256(inputJson()))
                .containsEntry("claimToken",CLAIM).containsEntry("proofNonce",NONCE).containsEntry("modelDigest",DIGEST)
                .containsEntry("leaseExpiresAt",NOW.plusHours(24).toInstant(ZoneOffset.UTC).toEpochMilli());
        verify(mapper,never()).register(any(),any()); verify(mapper,never()).insert(any(Execution.class));
        verify(mapper,never()).heartbeat(anyLong(),any(),any(),any());
        verify(mapper,never()).saveResult(anyString(),anyString(),any(),anyString(),anyString());
        verify(mapper,never()).cancelUnexecuted(anyLong(),anyLong(),anyString(),anyString(),any());
        verify(service,never()).infer(any()); verifyNoInteractions(settlement,audit);
    }
    @Test void manifestRequiresTheOriginalNonNullNextKeyAndExactExecutionIdentity() {
        resumableTask();
        for(String key:new String[]{null,"","bad"})
            assertThatThrownBy(()->service.manifest(identity,TASK,key)).hasMessage("TEST_CLOUD_IDEMPOTENCY_INVALID");
        assertThatThrownBy(()->service.manifest(identity,TASK,"BGE-NEXT-"+"e".repeat(32))).hasMessage("TEST_CLOUD_NEXT_KEY_INVALID");
        var e=resumableExecution(null,null,null);
        when(mapper.lock(TASK)).thenReturn(new Execution(TASK,"other-executor",e.ownerId(),e.deviceId(),e.instanceNo(),e.taskConfigId(),
                e.nextKeyHash(),e.claimToken(),e.inputJson(),e.inputHash(),e.modelTag(),e.modelDigest(),e.proofNonce(),null,null,null,null,e.createdAt()));
        assertThatThrownBy(()->service.manifest(identity,TASK,RESUME_KEY)).hasMessage("TEST_CLOUD_MANIFEST_INVALID");
        verifyNoInteractions(settlement,audit);
    }
    @Test void manifestRefusesAnyExistingResultCompleteIntentOrFinancialEvidence() {
        resumableTask();
        for(Execution execution:List.of(resumableExecution("e".repeat(64),null,null),resumableExecution(null,"e".repeat(64),null),
                resumableExecution(null,null,"e".repeat(64)))) {
            when(mapper.lock(TASK)).thenReturn(execution);
            assertThatThrownBy(()->service.manifest(identity,TASK,RESUME_KEY)).hasMessage("TEST_CLOUD_RESUME_REQUIRES_RECONCILIATION");
        }
        when(mapper.lock(TASK)).thenReturn(resumableExecution(null,null,null));
        when(mapper.settlementEvidence(TASK)).thenReturn(1);
        assertThatThrownBy(()->service.manifest(identity,TASK,RESUME_KEY)).hasMessage("TEST_CLOUD_RESUME_REQUIRES_RECONCILIATION");
        verifyNoInteractions(settlement,audit); verify(service,never()).infer(any());
    }
    @Test void manifestRequiresExactlyOneActiveTaskAndCannotRenewExpiredLeaseOrProof() {
        resumableTask();
        when(mapper.countActive(7,11)).thenReturn(2);
        assertThatThrownBy(()->service.manifest(identity,TASK,RESUME_KEY)).hasMessage("TEST_CLOUD_ACTIVE_TASK_CONFLICT");
        when(mapper.countActive(7,11)).thenReturn(1);
        when(assignments.lockActiveAssignment(7L,11L,"PRODUCTION")).thenReturn(null);
        assertThatThrownBy(()->service.manifest(identity,TASK,RESUME_KEY)).hasMessage("TEST_CLOUD_ACTIVE_TASK_CONFLICT");
        when(assignments.lockActiveAssignment(7L,11L,"PRODUCTION")).thenReturn(task("RUNNING"));
        for(boolean expiredLease:new boolean[]{true,false}) {
            when(assignments.lockAssignment(7L,TASK,"PRODUCTION")).thenReturn(new AssignmentRow(TASK,11L,"EM-UNIT",TASK_NAME,"EM",KIND,CLIENT,
                    "RUNNING",BigDecimal.ONE,5,0,NOW.minusMinutes(5),expiredLease?NOW.minusSeconds(1):NOW.plusHours(1),null,null,NONCE,
                    expiredLease?NOW.plusHours(1):NOW.minusSeconds(1)));
            assertThatThrownBy(()->service.manifest(identity,TASK,RESUME_KEY)).hasMessage("TEST_CLOUD_TASK_INVALID");
        }
        verify(mapper,never()).saveResult(anyString(),anyString(),any(),anyString(),anyString()); verifyNoInteractions(settlement,audit);
    }
    @Test void manifestRechecksDeactivationOrPausedOwnershipBeforeReturningSensitiveClaimFields() {
        resumableTask();
        when(assignments.lockPaidCloudShareDailyNex(7L,11L)).thenReturn(null);
        assertThatThrownBy(()->service.manifest(identity,TASK,RESUME_KEY)).hasMessage("TEST_CLOUD_BINDING_INVALID");
        when(assignments.lockPaidCloudShareDailyNex(7L,11L)).thenReturn(new BigDecimal("3"));
        when(assignments.lockOwnedDevice(7L,11L)).thenReturn(new DeviceRow(11L,"NEX-UNIT-CLOUD","SHARE","SHARE","Cloud","INACTIVE",
                "cloud-share",NOW.minusDays(1),NOW.minusHours(1),24,"unit","OFFLINE",null,false));
        assertThatThrownBy(()->service.manifest(identity,TASK,RESUME_KEY)).hasMessage("TEST_CLOUD_BINDING_INVALID");
        verifyNoInteractions(settlement,audit);
    }
    @Test void manifestResumesTheExistingPendingDeactivationTaskButStillRejectsActualRevocation() {
        Identity target=fleetIdentity();
        when(assignments.lockOwnedDevice(7L,12L)).thenReturn(new DeviceRow(12L,"NEX-FLEET-12","DEVICE","S1","S1","ACTIVE",
                "stellarbox-s1",NOW.minusDays(1),NOW.minusHours(1),96,"unit","ONLINE",null,false));
        var e=resumableExecution(null,null,null);
        when(mapper.lock(TASK)).thenReturn(new Execution(TASK,"fleet-12",7,12,"NEX-FLEET-12","EM-UNIT",e.nextKeyHash(),CLAIM,
                e.inputJson(),e.inputHash(),e.modelTag(),e.modelDigest(),NONCE,null,null,null,null,NOW));
        when(assignments.lockAssignment(7L,TASK,"PRODUCTION")).thenReturn(fleetTask(TASK,KIND,CLIENT));
        when(assignments.lockActiveAssignment(7L,12L,"PRODUCTION")).thenReturn(fleetTask(TASK,KIND,CLIENT));
        when(mapper.countActive(7,12)).thenReturn(1);
        // A pending stop rejects fresh dispatch but permits finishing this already manifested task.
        when(mapper.liveFleetBinding(NOW.minusDays(1),null,7,12,"NEX-FLEET-12",false,false)).thenReturn(null);
        when(mapper.liveFleetBinding(NOW.minusDays(1),null,7,12,"NEX-FLEET-12",true,false)).thenReturn(1);
        assertThat(service.manifest(target,TASK,RESUME_KEY).getData()).containsEntry("resumeAllowed",true);
        verify(mapper,never()).liveFleetBinding(NOW.minusDays(1),null,7,12,"NEX-FLEET-12",false,false);
        when(mapper.liveFleetBinding(NOW.minusDays(1),null,7,12,"NEX-FLEET-12",true,false)).thenReturn(null);
        assertThatThrownBy(()->service.manifest(target,TASK,RESUME_KEY)).hasMessage("TEST_CLOUD_BINDING_INVALID");
        verifyNoInteractions(settlement,audit);
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
