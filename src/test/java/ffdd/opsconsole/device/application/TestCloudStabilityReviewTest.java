package ffdd.opsconsole.device.application;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import ffdd.opsconsole.device.mapper.*;
import ffdd.opsconsole.device.mapper.TestCloudExecutorMapper.*;
import ffdd.opsconsole.shared.api.ApiResult;
import ffdd.opsconsole.shared.audit.AuditLogService;
import java.time.*;
import java.util.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
class TestCloudStabilityReviewTest {
    record Scope(long device,String task,String instance,String originalHash,String nextHash) { }
    static final Scope[] SCOPES={
        new Scope(1204,"CTA-F1D89023D75F473E8D7A57F4B0E6662D","DEV-TI-27D1233EBF21434FB85EC6B49D66507F","4f9d90a0bf0f66a7fb6b05bce77e2670e53551a49b1d2c446a21665eaa0d239f","ffc90539ef743e37a71e24bf092e44736cae4a71879e75303759d17f61281c80"),
        new Scope(1209,"CTA-863FE539FA4943CB8695283A2BDCDB5F","NEX-ORD-5A61BFA35341348072293238","e86c62a5dedae748aada2e98285f90e10f2c5b89e8eb46542760f9033928c962","126e0723f6e5d1bcb1cb9842e48f1dfb2da41ec9868e6c80834077c68960d2f5"),
        new Scope(1214,"CTA-D8C51049769A469FB39DB21FEFA5374C","DEV-CPR-A70B39752516410385509B908766F165","c5701921271e89cff7c3411f60cda32ef283670b2459b65953a655a97f5e19e4","7cb68b22fee7d81c27f8150559752474ef4bf4762d0d84fb7c689afb447cf549")};
    final AppTaskAssignmentMapper assignments=mock(AppTaskAssignmentMapper.class);
    final TestCloudExecutorMapper mapper=mock(TestCloudExecutorMapper.class);
    final TestCloudRequests requests=mock(TestCloudRequests.class);
    final AppTaskAssignmentService settlement=mock(AppTaskAssignmentService.class);
    final ObjectMapper json=new ObjectMapper();
    TestCloudExecutorService service;
    TestCloudExecutorService.Identity identity;
    TestCloudExecutorService.CompleteRequest body;
    String key;
    Map<String,Object> detail;
    Scope scope;
    void fixture(int index) throws Exception {
        scope=SCOPES[index]; var now=TestCloudExecutorServiceTest.NOW;
        var env=TestCloudExecutorServiceTest.environment();
        env.setProperty(TestCloudExecutorService.PREFIX+"fleet-enabled","true");
        env.setProperty(TestCloudExecutorService.PREFIX+"fleet-started-at",now.minusDays(1).toString());
        identity=new TestCloudExecutorService.Identity("fleet-"+scope.device(),7,scope.device(),scope.instance(),"EM-UNIT","bge-m3:567m",TestCloudExecutorServiceTest.DIGEST);
        service=new TestCloudExecutorService(assignments,mapper,settlement,mock(AuditLogService.class),Clock.fixed(TestCloudExecutorServiceTest.INSTANT,ZoneOffset.UTC),env,requests);
        doAnswer(call->((java.util.function.Supplier<?>)call.getArgument(0)).get()).when(requests).outside(any());
        doAnswer(call->((java.util.function.Supplier<?>)call.getArgument(0)).get()).when(requests).db(any());
        doReturn(ApiResult.ok(Map.of("accepted",true))).when(requests).execute(any(),anyString(),anyString(),anyString(),nullable(String.class),nullable(Long.class),nullable(String.class),nullable(Long.class),any());
        var binding=new Binding(identity.executorId(),7,scope.device(),scope.instance(),"EM-UNIT",identity.model(),identity.modelDigest());
        when(mapper.binding(identity.executorId())).thenReturn(binding); when(mapper.lockBinding(identity.executorId())).thenReturn(binding);
        when(assignments.lockProductionUser(7L)).thenReturn(7L);
        when(assignments.lockOwnedDevice(7L,scope.device())).thenReturn(new AppTaskAssignmentMapper.DeviceRow(scope.device(),scope.instance(),"DEVICE","S1","S1","ACTIVE","stellarbox-s1",now.minusDays(1),now.minusHours(1),96,"unit","ONLINE",null,false));
        var original=TestCloudExecutorServiceTest.task("RUNNING");
        var task=new AppTaskAssignmentMapper.AssignmentRow(scope.task(),scope.device(),"EM-UNIT",original.taskName(),"EM",TestCloudExecutorService.KIND,TestCloudExecutorService.CLIENT,"RUNNING",original.rewardUsdt(),5,0,now.minusMinutes(25),now.plusHours(24),null,null,TestCloudExecutorServiceTest.NONCE,now.plusHours(24));
        when(assignments.lockAssignment(7L,scope.task(),"PRODUCTION")).thenReturn(task);
        var e=new Execution(scope.task(),identity.executorId(),7,scope.device(),scope.instance(),"EM-UNIT",scope.nextHash(),"b".repeat(64),TestCloudExecutorServiceTest.inputJson(),TestCloudRollbackReviewServiceTest.INPUT,identity.model(),identity.modelDigest(),TestCloudExecutorServiceTest.NONCE,null,null,null,null,now.minusMinutes(25));
        when(mapper.lock(scope.task())).thenReturn(e); when(mapper.readActiveExecution(identity.executorId(),7,scope.device())).thenReturn(e);
        when(mapper.rollbackUnconsumedTask(scope.task(),7,scope.device())).thenReturn(1);
        key="BGE-COMPLETE-"+TestCloudExecutorService.sha256(scope.task());
        body=new TestCloudExecutorService.CompleteRequest(TestCloudExecutorService.SPEC,e.inputHash(),identity.modelDigest(),"unused",TestCloudRollbackReviewServiceTest.RESULT,e.proofNonce(),TestCloudExecutorServiceTest.INSTANT.toEpochMilli(),e.claimToken());
        detail=new LinkedHashMap<>();
        detail.putAll(Map.of("version","UVEL_TEST_CLOUD_STABILITY_RECOVERY_V1","deploymentScope","TEST","rollbackConfirmed",true,"maxAttempts",1));
        detail.putAll(Map.of("ownerId",7,"deviceId",scope.device(),"instanceNo",scope.instance(),"taskNo",scope.task(),"executorId",identity.executorId(),"taskConfigId","EM-UNIT","nextKeyHash",e.nextKeyHash(),"inputHash",e.inputHash(),"resultHash",body.resultHash()));
        detail.putAll(Map.of("model",identity.model(),"modelDigest",identity.modelDigest(),"proofNonceHash",TestCloudExecutorService.sha256(e.proofNonce()),"completeKeyHash",TestCloudExecutorService.sha256(key),"originalRequestHash",scope.originalHash()));
        for(String field:List.of("evidenceSha256","sourceClosureSha256","quiescenceSha256","lockedZeroEvidenceSha256","originalCallSetSha256")) detail.put(field,"e".repeat(64));
        review();
    }
    void review() throws Exception {
        when(requests.stabilityReviews(scope.task(),7)).thenReturn(List.of(new TestCloudRequestMapper.LegacyReview(91,"root-current-stability-review",json.writeValueAsString(detail),TestCloudExecutorServiceTest.NOW.minusMinutes(1))));
    }
    Object complete(Long review) { return service.complete(identity,scope.task(),key,body,"a".repeat(64),null,null,review); }
    @ParameterizedTest @ValueSource(ints={0,1,2}) void exactThreeRequireIndependentAuditAndSpendIndependentBudget(int index) throws Exception {
        fixture(index); assertThatThrownBy(()->complete(null)).hasMessage("TEST_CLOUD_STABILITY_REVIEW_REQUIRED");
        assertThat(complete(91L)).isNotNull();
        verify(requests).consumeStabilityReview(eq(identity),eq(scope.task()),eq(key),eq("a".repeat(64)),any(),eq(scope.originalHash()),eq(false));
        verifyNoInteractions(settlement); verify(mapper,never()).rollbackReviews(anyString(),anyLong());
    }
    @ParameterizedTest @ValueSource(strings={"originalRequestHash","nextKeyHash","completeKeyHash","resultHash","proofNonceHash","sourceClosureSha256","quiescenceSha256","originalCallSetSha256","rollbackConfirmed"})
    void missingOrForeignEvidenceNeverSpendsBudget(String field) throws Exception {
        fixture(0); detail.remove(field); review();
        assertThatThrownBy(()->complete(91L)).hasMessage("TEST_CLOUD_STABILITY_REVIEW_INVALID");
        verify(requests,never()).consumeStabilityReview(any(),anyString(),anyString(),anyString(),any(),anyString(),anyBoolean());
    }
    @ParameterizedTest @ValueSource(longs={1152,1380}) void protectedForeignDevicesCannotBorrowAnyOfTheThreeReviewsOrForgeNextSource(long device) throws Exception {
        fixture(0);
        identity=new TestCloudExecutorService.Identity("fleet-"+device,7,device,scope.instance(),"EM-UNIT",identity.model(),identity.modelDigest());
        var binding=new Binding(identity.executorId(),7,device,scope.instance(),"EM-UNIT",identity.model(),identity.modelDigest());
        when(mapper.binding(identity.executorId())).thenReturn(binding);
        assertThatThrownBy(()->complete(91L)).hasMessage("TEST_CLOUD_STABILITY_SCOPE_INVALID");
        var e=mapper.lock(scope.task());
        when(mapper.readActiveExecution(identity.executorId(),7,device)).thenReturn(e);
        assertThatThrownBy(()->service.next(identity,"new-next-alias-unit")).hasMessage("TEST_CLOUD_LEGACY_NEXT_RECONCILE_ONLY");
        verify(requests,never()).execute(any(),eq("NEXT"),anyString(),anyString(),any(),any(),any(),any(),any());
    }
    @Test void existingLegacyOriginalNextKeyCannotBeConvertedIntoDurableProvenance() throws Exception {
        fixture(0);
        String original="BGE-NEXT-"+"a".repeat(32);
        var execution=mapper.lock(scope.task());
        when(mapper.readByNextKey(identity.executorId(),TestCloudExecutorService.sha256(original))).thenReturn(execution);
        assertThatThrownBy(()->service.next(identity,original)).hasMessage("TEST_CLOUD_LEGACY_NEXT_RECONCILE_ONLY");
        verify(requests,never()).execute(any(),eq("NEXT"),anyString(),anyString(),any(),any(),any(),any(),any());
    }
}
