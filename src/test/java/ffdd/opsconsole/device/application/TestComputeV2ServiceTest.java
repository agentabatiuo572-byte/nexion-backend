package ffdd.opsconsole.device.application;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import ffdd.opsconsole.device.compute.v2.*;
import ffdd.opsconsole.device.compute.v2.ComputeV2Event.*;
import ffdd.opsconsole.device.mapper.*;
import ffdd.opsconsole.shared.exception.BizException;
import ffdd.opsconsole.shared.storage.ObjectStorageService;
import java.io.ByteArrayInputStream;
import java.math.BigDecimal;
import java.nio.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.junit.jupiter.api.*;
import org.springframework.mock.env.MockEnvironment;
import org.springframework.transaction.support.TransactionSynchronizationManager;

class TestComputeV2ServiceTest {
    static final Instant NOW=Instant.parse("2026-11-01T23:59:55Z");
    static final String TASK="CV2-"+"A".repeat(32),WALLET="11111111-1111-1111-1111-111111111111";
    static final String SECRET="a".repeat(64),MODEL="sha256:"+"b".repeat(64);
    final ComputeV2Runtime runtime=mock(ComputeV2Runtime.class);
    final TestCloudExecutorService cloud=mock(TestCloudExecutorService.class);
    final TestCloudRequests transactions=mock(TestCloudRequests.class);
    final AppTaskAssignmentMapper assignments=mock(AppTaskAssignmentMapper.class);
    final TaskSchedulingPolicyMapper policies=mock(TaskSchedulingPolicyMapper.class);
    final ObjectStorageService objects=mock(ObjectStorageService.class);
    final ComputeV2AdmissionMapper admission=mock(ComputeV2AdmissionMapper.class);
    final TestCloudActivationAdmission physicalAdmission=mock(TestCloudActivationAdmission.class);
    final ObjectMapper json=new ObjectMapper().findAndRegisterModules();
    final MutableClock clock=new MutableClock();
    final TestCloudExecutorService.Identity caller=new TestCloudExecutorService.Identity("fleet",0,0,"fleet","EM-A","bge-m3:567m",MODEL);
    final TestCloudExecutorService.Identity device=new TestCloudExecutorService.Identity("fleet-11",7,11,"DEV-11","EM-A","bge-m3:567m",MODEL);
    final AtomicReference<ComputeV2Runtime.RuntimeView> current=new AtomicReference<>();
    final AtomicReference<Verified> accepted=new AtomicReference<>();
    TestComputeV2Service service;TestComputeV2Service.Complete proof;byte[] artifact;TaskSnapshot task;MockEnvironment environment;

    @BeforeEach void setup()throws Exception{
        var env=new MockEnvironment().withProperty("nexion.compute-v2.deployment-scope","TEST")
                .withProperty("nexion.compute-v2.device-ids","11").withProperty("nexion.compute-v2.allowed-task-ids","EM-A,EM-B")
                .withProperty("nexion.compute-v2.proof-secret",SECRET).withProperty("nexion.compute-v2.validator-container-id","f".repeat(64));
        for(int n=1;n<=6;n++)env.setProperty("nexion.compute-v2.runner-"+n+"-container-id",Integer.toString(n).repeat(64));
        environment=env;
        service=new TestComputeV2Service(runtime,cloud,transactions,assignments,policies,objects,admission,env,json,clock,Optional.empty(),physicalAdmission);
        var input=new TestComputeV2Service.Manifest(2,"test-bge-m3-v1","test-bge-m3-v1","EM-A",9,"bge-m3:567m",MODEL,300,List.of("English real input","中文真实输入","third real input"));
        byte[] bytes=json.writeValueAsBytes(input);String hash=TestCloudExecutorService.sha256(bytes);
        task=new TaskSnapshot("next-11","EM-A","Real embedding","EM",TestCloudExecutorService.KIND,"UVEL Compute",9,5,5,5,5,300,0,
                NOW.minusSeconds(20),NOW.minusSeconds(10),NOW.plusSeconds(35),NOW.plusSeconds(400),123456,112233,new BigDecimal("1.1"),7,WALLET,
                new DeviceSnapshot("Cloud","CLOUD_SHARE","cloud-share",null,null,null,24),hash,"b".repeat(64),"compute-v2/TEST/input/"+hash+".json");
        current.set(view(event(Kind.STARTED,null),"COMPUTING"));
        when(runtime.forExecutor("compute-v2:runner-1")).thenAnswer(call->Optional.of(current.get()));
        when(runtime.view(7,11)).thenAnswer(call->Optional.of(current.get()));when(runtime.event(TASK)).thenAnswer(call->Optional.of(current.get().event()));
        when(objects.get(task.inputArtifactRef())).thenAnswer(call->new ByteArrayInputStream(bytes));
        when(cloud.computeV2Identity(caller,11)).thenReturn(device);
        when(transactions.db(any())).thenAnswer(call->((java.util.function.Supplier<?>)call.getArgument(0)).get());
        var owned=new AppTaskAssignmentMapper.DeviceRow(11L,"DEV-11","CLOUD_SHARE","SHARE","Cloud","ACTIVE","cloud-share",
                LocalDateTime.ofInstant(NOW.minusSeconds(86400),ZoneOffset.UTC),LocalDateTime.ofInstant(NOW.minusSeconds(3600),ZoneOffset.UTC),24,"DC","ONLINE",null,false);
        when(assignments.lockOwnedDevice(7L,11L)).thenReturn(owned);when(assignments.ownedDevices(7L)).thenReturn(List.of(owned));
        when(assignments.lockPaidCloudShareDailyNex(7L,11L)).thenReturn(new BigDecimal("3.000001"));
        when(assignments.lockDailyCloudShareNex(anyLong(),anyString())).thenReturn(null);
        when(admission.pendingDeactivate(11L,7L)).thenReturn(0);
        when(admission.lockWallets(7L)).thenReturn(List.of(Map.of("id",7L,"incarnation",WALLET)));
        double[][] vectors=new double[3][1024];for(var vector:vectors)Arrays.fill(vector,1.0);
        when(cloud.infer(eq(device),anyList())).thenReturn(vectors);
        artifact=new byte[12288];var buffer=ByteBuffer.wrap(artifact).order(ByteOrder.LITTLE_ENDIAN);while(buffer.hasRemaining())buffer.putFloat(1f);
        when(runtime.beginVerification(eq(7L),eq(11L),eq(1L),anyString())).thenReturn(true);
        when(runtime.verifiedComplete(eq(7L),eq(11L),eq(1L),any())).thenAnswer(call->{Verified v=call.getArgument(3);accepted.set(v);var e=event(Kind.VERIFIED,v);current.set(view(e,"COMPLETED"));return e;});
        var manifest=service.next(caller,"runner-1");
        proof=new TestComputeV2Service.Complete(1,manifest.get("claimToken").toString(),manifest.get("proofNonce").toString(),hash,MODEL,
                TestCloudExecutorService.sha256(artifact),Base64.getEncoder().encodeToString(artifact),NOW.getEpochSecond());
    }
    ComputeV2Event event(Kind kind,Verified v){return new ComputeV2Event(2,ComputeV2Event.eventId(TASK,1,kind),kind,"TEST","PRODUCTION","",7,11,"DEV-11",TASK,1,clock.instant(),task,v,null);}
    ComputeV2Runtime.RuntimeView view(ComputeV2Event e,String state){return new ComputeV2Runtime.RuntimeView(e,state,"compute-v2:runner-1",NOW.plusSeconds(35),0,0);}
    @Test void realProofAdapterPreservesFrozenQuoteAndPersistsOnlyVerifiedArtifact(){
        var result=service.complete(caller,"runner-1",TASK,proof);
        assertThat(result.get("state")).isEqualTo("COMPLETED");assertThat(accepted.get().rewardNexMicros()).isEqualTo(3000001);
        assertThat(accepted.get().artifactBytes()).isEqualTo(12288);assertThat(current.get().event().task().rewardUsdtMicros()).isEqualTo(123456);
        verify(cloud).requireComputeV2Paid(device,true);verify(objects).put(contains("/result/7/"+TASK+"/"),eq("application/octet-stream"),any(),eq(12288L));
        verify(runtime,never()).executionExited(anyLong(),anyLong(),anyLong(),anyString());
    }
    @Test void duplicateCompletionDoesNotInferOrPublishTwice(){
        service.complete(caller,"runner-1",TASK,proof);service.complete(caller,"runner-1",TASK,proof);
        verify(cloud,times(1)).infer(eq(device),anyList());verify(runtime,times(1)).verifiedComplete(eq(7L),eq(11L),eq(1L),any());
    }
    @Test void oldDailyLegacyRewardIsNotReissuedOrAllowedToPoisonTheUsdtBatch(){
        when(assignments.lockDailyCloudShareNex(7L,"CLOUD_SHARE_DAILY:11:2026-11-01")).thenReturn(99L);
        service.complete(caller,"runner-1",TASK,proof);assertThat(accepted.get().rewardNexMicros()).isZero();assertThat(accepted.get().dailyEntitlementKey()).isNull();
    }
    @Test void independentValidationCrossingUtcMidnightPreservesTheOriginalCompletionDay(){
        when(cloud.infer(eq(device),anyList())).thenAnswer(call->{clock.at=NOW.plusSeconds(10);double[][] rows=new double[3][1024];for(var row:rows)Arrays.fill(row,1d);return rows;});
        service.complete(caller,"runner-1",TASK,proof);
        assertThat(accepted.get().completedAt()).isEqualTo(NOW);assertThat(accepted.get().verifiedAt()).isEqualTo(NOW.plusSeconds(10));
        assertThat(accepted.get().dailyEntitlementKey()).isEqualTo("CLOUD_SHARE_DAILY:11:2026-11-01");
    }
    @Test void resultMustMatchIndependentInferenceAndFailureKeepsComputePermit(){
        when(cloud.infer(eq(device),anyList())).thenReturn(new double[3][1024]);
        assertThatThrownBy(()->service.complete(caller,"runner-1",TASK,proof)).isInstanceOf(BizException.class).hasMessage("TEST_CLOUD_RESULT_INVALID");
        verify(runtime).failVerification(eq(7L),eq(11L),eq(1L),anyString(),anyString());verify(runtime,never()).verifiedComplete(anyLong(),anyLong(),anyLong(),any());
        verify(runtime,never()).executionExited(anyLong(),anyLong(),anyLong(),anyString());
    }
    @Test void authorityRevocationAfterModelValidationNeverProducesAPayableEvent(){
        doThrow(new BizException(403,"TEST_CLOUD_BINDING_INVALID")).when(cloud).requireComputeV2Paid(device,true);
        assertThatThrownBy(()->service.complete(caller,"runner-1",TASK,proof)).hasMessage("TEST_CLOUD_BINDING_INVALID");
        verify(runtime,never()).verifiedComplete(anyLong(),anyLong(),anyLong(),any());
    }
    @Test void walletIncarnationChangeIsBlockedBeforeConfirmation(){
        when(admission.lockWallets(7L)).thenReturn(List.of(Map.of("id",7L,"incarnation","22222222-2222-2222-2222-222222222222")));
        assertThatThrownBy(()->service.complete(caller,"runner-1",TASK,proof)).hasMessage("COMPUTE_V2_WALLET_IDENTITY_CHANGED");
        verify(runtime,never()).verifiedComplete(anyLong(),anyLong(),anyLong(),any());
    }
    @Test void changedGenerationAndForeignRunnerCannotSubmitProof(){
        var changed=new TestComputeV2Service.Complete(2,proof.claimToken(),proof.proofNonce(),proof.inputHash(),MODEL,proof.resultHash(),proof.resultArtifactBase64(),proof.proofTimestamp());
        assertThatThrownBy(()->service.complete(caller,"runner-1",TASK,changed)).hasMessage("COMPUTE_V2_PROOF_BINDING_INVALID");
        when(runtime.forExecutor("compute-v2:runner-2")).thenReturn(Optional.empty());
        assertThatThrownBy(()->service.complete(caller,"runner-2",TASK,proof)).hasMessage("COMPUTE_V2_EXECUTOR_NOT_ASSIGNED");verify(cloud,never()).infer(any(),anyList());
    }
    @Test void anOuterSqlTransactionIsRejectedBeforeInference(){
        TransactionSynchronizationManager.setActualTransactionActive(true);
        try{assertThatThrownBy(()->service.complete(caller,"runner-1",TASK,proof)).hasMessage("COMPUTE_V2_INFERENCE_OUTER_TRANSACTION");}
        finally{TransactionSynchronizationManager.setActualTransactionActive(false);}
        verify(cloud,never()).infer(any(),anyList());
    }
    @Test void duplicateValidationCannotEraseTheActiveObserverGuard()throws Exception{
        var entered=new CountDownLatch(1);var release=new CountDownLatch(1);var failure=new AtomicReference<Throwable>();
        when(cloud.infer(eq(device),anyList())).thenAnswer(call->{entered.countDown();assertThat(release.await(5,TimeUnit.SECONDS)).isTrue();double[][] rows=new double[3][1024];for(var row:rows)Arrays.fill(row,1d);return rows;});
        Thread original=new Thread(()->{try{service.complete(caller,"runner-1",TASK,proof);}catch(Throwable error){failure.set(error);}});
        original.start();
        try{
            assertThat(entered.await(5,TimeUnit.SECONDS)).isTrue();
            assertThatThrownBy(()->service.complete(caller,"runner-1",TASK,proof)).hasMessage("COMPUTE_V2_VALIDATION_IN_PROGRESS");
            var observation=observation();
            assertThatThrownBy(()->service.observedExit(caller,"runner-1",TASK,1,proof.claimToken(),json.valueToTree(observation),sign(observation)))
                    .hasMessage("COMPUTE_V2_VALIDATOR_STILL_ACTIVE");
            verify(runtime,never()).executionObservedExit(anyLong(),anyLong(),anyLong(),anyString());
        }finally{release.countDown();original.join(5000);}
        assertThat(original.isAlive()).isFalse();assertThat(failure.get()).isNull();verify(cloud,times(1)).infer(eq(device),anyList());
    }
    @Test void queuedSlotScanningDoesNotBorrowDatabaseConnections(){
        when(runtime.isReady()).thenReturn(true);when(runtime.canAcceptWork()).thenReturn(true);when(runtime.viewDevice(11L)).thenReturn(Optional.of(view(event(Kind.STARTED,null),"QUEUED")));
        clearInvocations(admission,assignments,policies,transactions,cloud);
        service.issueEnrolled();verifyNoInteractions(admission,assignments,policies,transactions,cloud);
    }
    @Test void physicalReservationSharesLegacyGuardButMessageConfirmationRunsAfterSql(){
        var started=current.get().event();
        when(runtime.forExecutor("compute-v2:runner-1")).thenReturn(Optional.empty());
        when(runtime.canAcceptWork()).thenReturn(true);when(runtime.activeExecutionCount()).thenReturn(2);
        when(runtime.queued(12)).thenReturn(List.of(view(event(Kind.ISSUED,null),"QUEUED")));
        when(physicalAdmission.canStartV2Execution(2)).thenReturn(true);
        doAnswer(call->{
            TransactionSynchronizationManager.setActualTransactionActive(true);
            try{return ((java.util.function.Supplier<?>)call.getArgument(0)).get();}
            finally{TransactionSynchronizationManager.setActualTransactionActive(false);}
        }).when(transactions).db(any());
        when(runtime.reserveExecution(7,11,"compute-v2:runner-1")).thenAnswer(call->{
            assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isTrue();return Optional.of(started);
        });
        when(runtime.confirmExecution(started)).thenAnswer(call->{
            assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();return started;
        });
        clearInvocations(cloud,objects,runtime,physicalAdmission);
        assertThat(service.next(caller,"runner-1").get("state")).isEqualTo("COMPUTING");
        var ordered=inOrder(physicalAdmission,cloud,runtime);
        ordered.verify(physicalAdmission).lock();ordered.verify(physicalAdmission).canStartV2Execution(2);
        ordered.verify(cloud).computeV2Identity(caller,11);ordered.verify(runtime).reserveExecution(7,11,"compute-v2:runner-1");
        ordered.verify(runtime).confirmExecution(started);
        verify(runtime,never()).acquireExecution(anyLong(),anyLong(),anyString());
    }
    @Test void drainedOrFullHostLeavesQueuedTaskUnchangedWithoutOwnershipSqlOrMessageSend(){
        when(runtime.forExecutor("compute-v2:runner-1")).thenReturn(Optional.empty());when(runtime.canAcceptWork()).thenReturn(true);
        when(runtime.queued(12)).thenReturn(List.of(view(event(Kind.ISSUED,null),"QUEUED")));
        when(runtime.activeExecutionCount()).thenReturn(2);when(physicalAdmission.canStartV2Execution(2)).thenReturn(false);
        clearInvocations(cloud,assignments,admission,runtime);
        assertThat(service.next(caller,"runner-1").get("state")).isEqualTo("IDLE");
        verify(physicalAdmission).lock();verify(cloud).requireCurrent(caller);verifyNoMoreInteractions(cloud);verifyNoInteractions(assignments,admission);
        verify(runtime,never()).reserveExecution(anyLong(),anyLong(),anyString());verify(runtime,never()).confirmExecution(any());
        verify(runtime,never()).fail(anyLong(),anyLong(),anyLong(),anyString());
    }
    @Test void unknownStartAcknowledgementReconfirmsOriginalReservationEvenDuringDrain(){
        var reserved=view(event(Kind.STARTED,null),"STARTING");current.set(reserved);
        clearInvocations(runtime,transactions,physicalAdmission);
        service.next(caller,"runner-1");verify(runtime).confirmExecution(reserved.event());
        verifyNoInteractions(transactions,physicalAdmission);verify(runtime,never()).reserveExecution(anyLong(),anyLong(),anyString());
    }
    @Test void dispatchRejectsAnOuterSqlTransactionBeforePublishingOrReserving(){
        clearInvocations(runtime,transactions,physicalAdmission);
        TransactionSynchronizationManager.setActualTransactionActive(true);
        try{assertThatThrownBy(()->service.next(caller,"runner-1")).hasMessage("COMPUTE_V2_DISPATCH_OUTER_TRANSACTION");}
        finally{TransactionSynchronizationManager.setActualTransactionActive(false);}
        verifyNoInteractions(runtime,transactions,physicalAdmission);
    }
    @Test void completedDeviceIsDispatchedBeforeTheSixThousandSlotScanCursor(){
        service.complete(caller,"runner-1",TASK,proof);
        environment.setProperty("nexion.compute-v2.device-ids",java.util.stream.LongStream.rangeClosed(1,6000).mapToObj(Long::toString).collect(java.util.stream.Collectors.joining(",")));
        environment.setProperty(TestCloudExecutorService.PREFIX+"task-config-id","EM-A");environment.setProperty(TestCloudExecutorService.PREFIX+"model","bge-m3:567m");environment.setProperty(TestCloudExecutorService.PREFIX+"model-digest",MODEL);
        service=new TestComputeV2Service(runtime,cloud,transactions,assignments,policies,objects,admission,environment,json,clock,Optional.empty(),physicalAdmission);
        when(runtime.isReady()).thenReturn(true);when(runtime.canAcceptWork()).thenReturn(true);when(runtime.viewDevice(anyLong())).thenReturn(Optional.of(view(event(Kind.STARTED,null),"QUEUED")));
        when(runtime.viewDevice(11L)).thenReturn(Optional.empty());
        service.exited(caller,"runner-1",TASK,1,proof.claimToken());clearInvocations(runtime,cloud,transactions);
        service.issueEnrolled();
        var reads=mockingDetails(runtime).getInvocations().stream().filter(call->call.getMethod().getName().equals("viewDevice")).toList();
        assertThat(reads.get(0).getArgument(0,Long.class)).isEqualTo(11L);
        int firstFreeze=mockingDetails(cloud).getInvocations().stream().filter(call->call.getMethod().getName().equals("computeV2Identity")).findFirst().orElseThrow().getSequenceNumber();
        int firstOrdinaryScan=reads.stream().filter(call->Long.valueOf(1).equals(call.getArgument(0))).findFirst().orElseThrow().getSequenceNumber();
        assertThat(firstFreeze).isLessThan(firstOrdinaryScan);
    }
    @Test void exactObserverEvidenceAllowsFencedExitAndForgedOrForeignEvidenceCannotRelease(){
        var observation=observation();
        String signed=sign(observation);
        assertThatThrownBy(()->service.observedExit(caller,"runner-1",TASK,1,proof.claimToken(),json.valueToTree(observation),"0".repeat(64))).hasMessage("COMPUTE_V2_EXIT_EVIDENCE_INVALID");
        verify(runtime,never()).executionObservedExit(anyLong(),anyLong(),anyLong(),anyString());
        current.set(view(event(Kind.STARTED,null),"RECOVERING"));
        assertThat(service.observedExit(caller,"runner-1",TASK,1,proof.claimToken(),json.valueToTree(observation),signed).get("state")).isEqualTo("EXIT_CONFIRMED");
        verify(runtime).executionObservedExit(eq(7L),eq(11L),eq(1L),startsWith("compute-v2/TEST/recovery/"+TASK+"/"));
    }
    TreeMap<String,Object> observation(){var observation=new TreeMap<String,Object>();observation.put("taskNo",TASK);observation.put("generation",1);observation.put("runner","runner-1");
        observation.put("observedAt",NOW.toString());observation.put("computeContainerId","1".repeat(64));observation.put("validatorContainerId","f".repeat(64));observation.put("computeExitCode",137);observation.put("validatorExitCode",0);return observation;}
    String sign(Map<String,Object> observation){try{Mac h=Mac.getInstance("HmacSHA256");h.init(new SecretKeySpec(HexFormat.of().parseHex(SECRET),"HmacSHA256"));return HexFormat.of().formatHex(h.doFinal(json.writeValueAsBytes(observation)));}catch(Exception e){throw new RuntimeException(e);}}
    static final class MutableClock extends Clock{Instant at=NOW;public ZoneId getZone(){return ZoneOffset.UTC;}public Clock withZone(ZoneId zone){return this;}public Instant instant(){return at;}}
}
