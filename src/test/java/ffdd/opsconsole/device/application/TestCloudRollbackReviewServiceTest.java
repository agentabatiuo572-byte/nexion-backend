package ffdd.opsconsole.device.application;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import ffdd.opsconsole.device.mapper.AppTaskAssignmentMapper;
import ffdd.opsconsole.device.mapper.AppTaskAssignmentMapper.AssignmentRow;
import ffdd.opsconsole.device.mapper.AppTaskAssignmentMapper.DeviceRow;
import ffdd.opsconsole.device.mapper.TestCloudExecutorMapper;
import ffdd.opsconsole.device.mapper.TestCloudExecutorMapper.Binding;
import ffdd.opsconsole.device.mapper.TestCloudExecutorMapper.Execution;
import ffdd.opsconsole.device.mapper.TestCloudExecutorMapper.RollbackReviewRow;
import ffdd.opsconsole.shared.audit.AuditLogService;
import java.time.*;
import java.math.BigDecimal;
import java.util.*;
import org.apache.ibatis.annotations.Select;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class TestCloudRollbackReviewServiceTest {
    static final long OWNER=60723153007L;
    static final LocalDateTime NOW=LocalDateTime.of(2026,10,30,20,30);
    static final String NEXT="BGE-NEXT-"+"d".repeat(32),NONCE="a".repeat(64);
    static final String INPUT="400cf267a947b6e8bf374d1ea61c6013391618505a7b934a16b099853a8fcc6a";
    static final String RESULT="3d47ee75a2c38bd87d4a8d340d60298eb7decf8d15f09e15aef86b62c46a6187";
    final AppTaskAssignmentMapper assignments=mock(AppTaskAssignmentMapper.class);
    final TestCloudExecutorMapper mapper=mock(TestCloudExecutorMapper.class);
    final AppTaskAssignmentService settlement=mock(AppTaskAssignmentService.class);
    final AuditLogService audit=mock(AuditLogService.class);
    final ObjectMapper json=new ObjectMapper();
    TestCloudExecutorService service;
    TestCloudExecutorService.Identity identity;
    String task,instance,originalHash,complete;
    Execution execution;
    AssignmentRow assignment;
    Map<String,Object> detail;

    void fixture(long device) throws Exception {
        task=switch((int)device) {
            case 1204 -> "CTA-FDDE9396AE554345A116E4026D5590A4";
            case 1206 -> "CTA-DECD77CE4A6A47029334B35142A3B23D";
            case 1209 -> "CTA-E9CA6111BC2841C3A87A7E1B4ED478DE";
            case 1212 -> "CTA-56A3A0D522F6404C9E4AE65A6478A052";
            default -> throw new IllegalArgumentException("Unknown reviewed test device");
        };
        instance=switch((int)device) {
            case 1204 -> "DEV-TI-27D1233EBF21434FB85EC6B49D66507F";
            case 1206 -> "NEX-ORD-89A41D4C6261D157C6D20830";
            case 1209 -> "NEX-ORD-5A61BFA35341348072293238";
            case 1212 -> "DEV-TI-ADFCB790173045B89BC7E90F1058F275";
            default -> throw new IllegalArgumentException("Unknown reviewed test device");
        };
        originalHash=switch((int)device) {
            case 1204 -> "3bd943a7efe51ac4e190fe6cc37c075627f615e6479d79097fc57f51c342e54d";
            case 1206 -> "fdbb7d70769efda6050bc09e0a45dfb1d86b4da3376010bf532b2b688725b114";
            case 1209 -> "ad8bad588e5adfc2252c554fef896e709349684114c59b5559f830a43fdf4267";
            case 1212 -> "ac1a286bb35368bd50254db9b0d4ae6aee478654696f4bce73de635411f62d2a";
            default -> throw new IllegalArgumentException("Unknown reviewed test device");
        };
        String config=device==1209 || device==1212?"E2-20260924-EM":"EM-UNIT";
        complete="BGE-COMPLETE-"+TestCloudExecutorService.sha256(task);
        var env=TestCloudExecutorServiceTest.environment();
        env.setProperty(TestCloudExecutorService.PREFIX+"fleet-enabled","true");
        env.setProperty(TestCloudExecutorService.PREFIX+"fleet-started-at",NOW.minusDays(1).toString());
        service=new TestCloudExecutorService(assignments,mapper,settlement,audit,
                Clock.fixed(NOW.toInstant(ZoneOffset.ofHours(8)),ZoneOffset.ofHours(8)),env);
        identity=new TestCloudExecutorService.Identity("fleet-"+device,OWNER,device,instance,config,"bge-m3:567m",TestCloudExecutorServiceTest.DIGEST);
        when(mapper.binding(identity.executorId())).thenReturn(new Binding(identity.executorId(),OWNER,device,instance,config,identity.model(),identity.modelDigest()));
        when(assignments.lockProductionUser(OWNER)).thenReturn(OWNER);
        when(assignments.lockOwnedDevice(OWNER,device)).thenReturn(new DeviceRow(device,instance,"SERVER","S1","unit","ACTIVE",
                "stellarbox-s1",NOW.minusDays(1),NOW.minusHours(1),96,"unit","ONLINE",null,false));
        assignment=new AssignmentRow(task,device,config,TestCloudExecutorService.TASK_NAME,"EM",TestCloudExecutorService.KIND,
                TestCloudExecutorService.CLIENT,"RUNNING",new BigDecimal("0.045005"),5,0,NOW.minusMinutes(25),NOW.plusHours(24),
                null,null,NONCE,NOW.plusHours(24));
        when(assignments.lockAssignment(OWNER,task,"PRODUCTION")).thenReturn(assignment);
        when(assignments.lockActiveAssignment(OWNER,device,"PRODUCTION")).thenReturn(assignment);
        when(mapper.countActive(OWNER,device)).thenReturn(1);
        when(mapper.liveFleetBinding(NOW.minusDays(1),null,OWNER,device,instance,true,false)).thenReturn(1);
        execution=new Execution(task,identity.executorId(),OWNER,device,instance,config,TestCloudExecutorService.sha256(NEXT),
                "b".repeat(64),TestCloudExecutorServiceTest.inputJson(),INPUT,identity.model(),identity.modelDigest(),NONCE,
                null,null,null,null,NOW.minusMinutes(25));
        when(mapper.lock(task)).thenReturn(execution);
        when(mapper.rollbackUnconsumedTask(task,OWNER,device)).thenReturn(1);
        detail=new LinkedHashMap<>();
        detail.putAll(Map.of("version","UVEL_TEST_CLOUD_ROLLBACK_REVIEW_V1","deploymentScope","TEST",
                "attributionMode","EXHAUSTIVE_TWO_REQUEST_SET","rollbackConfirmed",true,"maxAttempts",1));
        detail.putAll(Map.of("ownerId",OWNER,"deviceId",device,"instanceNo",instance,"taskNo",task,
                "executorId",identity.executorId(),"taskConfigId",config,"nextKeyHash",execution.nextKeyHash(),
                "inputHash",INPUT,"resultHash",RESULT));
        detail.putAll(Map.of("model",identity.model(),"modelDigest",identity.modelDigest(),"proofNonceHash",TestCloudExecutorService.sha256(NONCE),
                "completeKeyHash",TestCloudExecutorService.sha256(complete),"originalRequestHash",originalHash));
        for(String field:List.of("evidenceSha256","requestSetSha256","serverErrorSetSha256","transactionProofSha256","quiescenceSha256","lockedZeroEvidenceSha256"))
            detail.put(field,"e".repeat(64));
        review(detail);
    }

    void review(Map<String,Object> value) throws Exception {
        when(mapper.rollbackReviews(task,OWNER)).thenReturn(List.of(new RollbackReviewRow(91,"root-reviewed-two-request-set",
                json.writeValueAsString(value),NOW.minusMinutes(1))));
    }
    Map<String,Object> read() { return service.rollbackReview(identity,task,complete,NEXT,new TestCloudExecutorService.RollbackReviewRequest(originalHash,RESULT)).getData(); }

    @ParameterizedTest @ValueSource(longs={1204,1206,1209,1212})
    void onlyExactReviewedRequestsExposeReadOnlyOneUseAuthorization(long device) throws Exception {
        fixture(device);
        assertThat(read()).containsEntry("rollbackConfirmed",true).containsEntry("maxAttempts",1)
                .containsEntry("reviewId",91L).containsEntry("nextKeyHash",execution.nextKeyHash())
                .doesNotContainKeys("claimToken","proofNonce","inputTexts","originalNextKey");
        verifyNoInteractions(settlement,audit);
        verify(mapper,never()).insert(any(Execution.class)); verify(mapper,never()).saveResult(any(),any(),any(),any(),any());
        verify(mapper,never()).heartbeat(anyLong(),any(),any(),any());
    }

    @ParameterizedTest @ValueSource(strings={"ownerId","deviceId","instanceNo","taskNo","originalRequestHash","resultHash",
            "inputHash","nextKeyHash","completeKeyHash","proofNonceHash","modelDigest","attributionMode","deploymentScope",
            "rollbackConfirmed","maxAttempts","requestSetSha256","quiescenceSha256","lockedZeroEvidenceSha256"})
    void incompleteOrDifferentTrustedAuditCannotAuthorize(String field) throws Exception {
        fixture(1204); detail.remove(field); review(detail);
        assertThatThrownBy(this::read).hasMessage("TEST_CLOUD_ROLLBACK_REVIEW_INVALID");
        verifyNoInteractions(settlement,audit);
    }

    @ParameterizedTest @ValueSource(strings={"receipt","ledger","event","consumed","result","key","body","artifact"})
    void anyCurrentEconomicOrResultEvidenceForcesReconciliation(String evidence) throws Exception {
        fixture(1204);
        switch(evidence) {
            case "receipt" -> when(mapper.rollbackReceiptEvidence(task)).thenReturn(List.of(1L));
            case "ledger" -> when(mapper.rollbackLedgerEvidence(task)).thenReturn(List.of(1L));
            case "event" -> when(mapper.rollbackEventEvidence(task)).thenReturn(List.of(1L));
            case "consumed" -> when(mapper.rollbackUnconsumedTask(task,OWNER,1204)).thenReturn(null);
            default -> when(mapper.lock(task)).thenReturn(new Execution(execution.taskNo(),execution.executorId(),OWNER,1204,instance,
                    execution.taskConfigId(),execution.nextKeyHash(),execution.claimToken(),execution.inputJson(),INPUT,execution.modelTag(),
                    execution.modelDigest(),NONCE,evidence.equals("result")?RESULT:null,evidence.equals("artifact")?new byte[1]:null,
                    evidence.equals("key")?"a".repeat(64):null,evidence.equals("body")?"b".repeat(64):null,execution.createdAt()));
        }
        assertThatThrownBy(this::read).hasMessage("TEST_CLOUD_ROLLBACK_REQUIRES_RECONCILIATION");
        verifyNoInteractions(settlement,audit);
    }

    @Test void negativeReadWithoutUniqueTrustedDecisionDoesNotAuthorize() throws Exception {
        fixture(1204);
        when(mapper.rollbackReviews(task,OWNER)).thenReturn(List.of());
        assertThatThrownBy(this::read).hasMessage("TEST_CLOUD_ROLLBACK_REVIEW_REQUIRED");
        var row=new RollbackReviewRow(91,"duplicate",json.writeValueAsString(detail),NOW.minusMinutes(1));
        when(mapper.rollbackReviews(task,OWNER)).thenReturn(List.of(row,row));
        assertThatThrownBy(this::read).hasMessage("TEST_CLOUD_ROLLBACK_REVIEW_REQUIRED");
    }

    @ParameterizedTest @ValueSource(longs={1209,1212})
    void newReviewedRequestsStillRequireUniqueExactAuditAndNoEconomicEvidence(long device) throws Exception {
        fixture(device);
        when(mapper.rollbackReviews(task,OWNER)).thenReturn(List.of());
        assertThatThrownBy(this::read).hasMessage("TEST_CLOUD_ROLLBACK_REVIEW_REQUIRED");
        var row=new RollbackReviewRow(91,"duplicate",json.writeValueAsString(detail),NOW.minusMinutes(1));
        when(mapper.rollbackReviews(task,OWNER)).thenReturn(List.of(row,row));
        assertThatThrownBy(this::read).hasMessage("TEST_CLOUD_ROLLBACK_REVIEW_REQUIRED");
        for(String field:List.of("ownerId","deviceId","instanceNo","taskNo","originalRequestHash","inputHash","resultHash")) {
            Object expected=detail.put(field,"foreign"); review(detail);
            assertThatThrownBy(this::read).as(field).hasMessage("TEST_CLOUD_ROLLBACK_REVIEW_INVALID");
            detail.put(field,expected);
        }
        review(detail);
        when(mapper.rollbackLedgerEvidence(task)).thenReturn(List.of(1L));
        assertThatThrownBy(this::read).hasMessage("TEST_CLOUD_ROLLBACK_REQUIRES_RECONCILIATION");
        verifyNoInteractions(settlement,audit);
    }

    @ParameterizedTest @ValueSource(longs={1209,1212})
    void newReviewedRequestsRejectIndividuallyMismatchedTaskConfiguration(long device) throws Exception {
        for(String source:List.of("binding","task","execution","audit")) {
            var fixture=new TestCloudRollbackReviewServiceTest(); fixture.fixture(device);
            var i=fixture.identity; var e=fixture.execution;
            String expected=switch(source) {
                case "binding" -> {
                    when(fixture.mapper.binding(i.executorId())).thenReturn(new Binding(i.executorId(),OWNER,device,i.instanceNo(),
                            "EM-OTHER",i.model(),i.modelDigest()));
                    yield "TEST_CLOUD_AUTH_INVALID";
                }
                case "task" -> {
                    when(fixture.assignments.lockAssignment(OWNER,fixture.task,"PRODUCTION")).thenReturn(
                            new AssignmentRow(fixture.task,device,"EM-OTHER",TestCloudExecutorService.TASK_NAME,"EM",TestCloudExecutorService.KIND,
                                    TestCloudExecutorService.CLIENT,"RUNNING",new BigDecimal("0.045005"),5,0,NOW.minusMinutes(25),
                                    NOW.plusHours(24),null,null,NONCE,NOW.plusHours(24)));
                    yield "TEST_CLOUD_TASK_INVALID";
                }
                case "execution" -> {
                    when(fixture.mapper.lock(fixture.task)).thenReturn(new Execution(e.taskNo(),e.executorId(),e.ownerId(),e.deviceId(),
                            e.instanceNo(),"EM-OTHER",e.nextKeyHash(),e.claimToken(),e.inputJson(),e.inputHash(),e.modelTag(),e.modelDigest(),
                            e.proofNonce(),e.resultHash(),e.resultArtifact(),e.completeKeyHash(),e.completeBodyHash(),e.createdAt()));
                    yield "TEST_CLOUD_MANIFEST_INVALID";
                }
                default -> {
                    fixture.detail.put("taskConfigId","EM-OTHER"); fixture.review(fixture.detail);
                    yield "TEST_CLOUD_ROLLBACK_REVIEW_INVALID";
                }
            };
            assertThatThrownBy(fixture::read).as(source).hasMessage(expected);
            verifyNoInteractions(fixture.settlement,fixture.audit);
        }
    }

    @Test void attemptBudgetCannotOverflowIntoOne() throws Exception {
        fixture(1204); detail.put("maxAttempts",4294967297L); review(detail);
        assertThatThrownBy(this::read).hasMessage("TEST_CLOUD_ROLLBACK_REVIEW_INVALID");
    }

    @ParameterizedTest @ValueSource(longs={1204,1206,1209,1212})
    void arbitraryUnknownOriginalKeyHashOrRequestCannotUseTheException(long device) throws Exception {
        fixture(device);
        assertThatThrownBy(()->service.rollbackReview(identity,task,complete,NEXT,new TestCloudExecutorService.RollbackReviewRequest("f".repeat(64),RESULT)))
                .hasMessage("TEST_CLOUD_ROLLBACK_REVIEW_INVALID");
        assertThatThrownBy(()->service.rollbackReview(identity,task,complete,"BGE-NEXT-"+"f".repeat(32),new TestCloudExecutorService.RollbackReviewRequest(originalHash,RESULT)))
                .hasMessage("TEST_CLOUD_ROLLBACK_REVIEW_INVALID");
        assertThatThrownBy(()->service.rollbackReview(identity,task,"BGE-COMPLETE-FOREIGN",NEXT,new TestCloudExecutorService.RollbackReviewRequest(originalHash,RESULT)))
                .hasMessage("TEST_CLOUD_ROLLBACK_REVIEW_INVALID");
        assertThatThrownBy(()->service.rollbackReview(identity,"CTA-OTHER-UNKNOWN",complete,NEXT,new TestCloudExecutorService.RollbackReviewRequest(originalHash,RESULT)))
                .hasMessage("TEST_CLOUD_ROLLBACK_REVIEW_INVALID");
        var unknown=new TestCloudExecutorService.Identity("fleet-1152",OWNER,1152,"legacy",identity.taskConfigId(),identity.model(),identity.modelDigest());
        when(mapper.binding("fleet-1152")).thenReturn(new Binding("fleet-1152",OWNER,1152,"legacy",unknown.taskConfigId(),unknown.model(),unknown.modelDigest()));
        assertThatThrownBy(()->service.rollbackReview(unknown,task,complete,NEXT,new TestCloudExecutorService.RollbackReviewRequest(originalHash,RESULT)))
                .hasMessage("TEST_CLOUD_ROLLBACK_REVIEW_INVALID");
        verifyNoInteractions(settlement,audit);
    }

    @ParameterizedTest @ValueSource(longs={1209,1212})
    void newReviewedScopesRejectOtherCurrentOwnerInstanceExecutorAndReviewedTask(long device) throws Exception {
        fixture(device);
        for(var other:List.of(
                new TestCloudExecutorService.Identity(identity.executorId(),OWNER+1,device,instance,identity.taskConfigId(),identity.model(),identity.modelDigest()),
                new TestCloudExecutorService.Identity(identity.executorId(),OWNER,device,"NEX-FOREIGN-INSTANCE",identity.taskConfigId(),identity.model(),identity.modelDigest()),
                new TestCloudExecutorService.Identity("fleet-9999",OWNER,device,instance,identity.taskConfigId(),identity.model(),identity.modelDigest()))) {
            when(mapper.binding(other.executorId())).thenReturn(new Binding(other.executorId(),other.ownerId(),other.deviceId(),other.instanceNo(),
                    other.taskConfigId(),other.model(),other.modelDigest()));
            assertThatThrownBy(()->service.rollbackReview(other,task,complete,NEXT,
                    new TestCloudExecutorService.RollbackReviewRequest(originalHash,RESULT)))
                    .hasMessage("TEST_CLOUD_ROLLBACK_REVIEW_INVALID");
        }
        when(mapper.binding(identity.executorId())).thenReturn(new Binding(identity.executorId(),OWNER,device,instance,
                identity.taskConfigId(),identity.model(),identity.modelDigest()));
        String otherTask=device==1209?"CTA-56A3A0D522F6404C9E4AE65A6478A052":"CTA-E9CA6111BC2841C3A87A7E1B4ED478DE";
        assertThatThrownBy(()->service.rollbackReview(identity,otherTask,"BGE-COMPLETE-"+TestCloudExecutorService.sha256(otherTask),NEXT,
                new TestCloudExecutorService.RollbackReviewRequest(originalHash,RESULT)))
                .hasMessage("TEST_CLOUD_ROLLBACK_REVIEW_INVALID");
        verifyNoInteractions(settlement,audit);
    }

    @Test void auditAndFinancialQueriesUseCurrentReadsAndOnlyTrustedSystemReview() throws Exception {
        for(String method:List.of("rollbackReceiptEvidence","rollbackLedgerEvidence","rollbackEventEvidence")) {
            String query=String.join(" ",TestCloudExecutorMapper.class.getMethod(method,String.class).getAnnotation(Select.class).value());
            assertThat(query).contains("FOR UPDATE").doesNotContain("is_deleted=0");
        }
        String query=String.join(" ",TestCloudExecutorMapper.class.getMethod("rollbackReviews",String.class,long.class).getAnnotation(Select.class).value());
        assertThat(query).contains("FOR UPDATE","actor_type='SYSTEM'","test:tasklock-parallel-rollback-20261030",
                "TEST_CLOUD_COMPLETION_ROLLBACK_CONFIRMED","result='SUCCESS'","risk_level='HIGH'");
    }
}
