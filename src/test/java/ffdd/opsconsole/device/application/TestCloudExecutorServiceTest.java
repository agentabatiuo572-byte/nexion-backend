package ffdd.opsconsole.device.application;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;
import static ffdd.opsconsole.device.application.TestCloudExecutorService.*;
import ffdd.opsconsole.device.mapper.*;
import ffdd.opsconsole.device.mapper.AppTaskAssignmentMapper.*;
import ffdd.opsconsole.device.mapper.TestCloudExecutorMapper.Execution;
import ffdd.opsconsole.device.application.TestCloudExecutorService.*;
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
    @Test void authoritativeForeignOrUnpaidOrPendingDeviceIsRejectedBeforeClaim() {
        when(mapper.eligibleBinding(7,11,"NEX-UNIT-CLOUD")).thenReturn(0);
        assertThatThrownBy(()->service.next(identity,"cloud-next-unit")).hasMessage("TEST_CLOUD_BINDING_INVALID");
        verifyNoInteractions(settlement); verify(mapper,never()).insert(any());
    }
    @Test void undeclaredUnmanifestedOldTaskIsNeverCancelledOrRebound() {
        when(assignments.lockActiveAssignment(7L,11L,"PRODUCTION")).thenReturn(task("RUNNING"));
        when(mapper.lock(TASK)).thenReturn(null);
        assertThatThrownBy(()->service.next(identity,"cloud-next-unit")).hasMessage("TEST_CLOUD_UNMANIFESTED_TASK_BLOCKED");
        verify(mapper,never()).cancelUnexecuted(anyLong(),anyLong(),anyString(),anyString(),any()); verifyNoInteractions(settlement);
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
