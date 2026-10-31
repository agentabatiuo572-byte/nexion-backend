package ffdd.opsconsole.device.application;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import ffdd.opsconsole.device.mapper.TestCloudRequestMapper;
import ffdd.opsconsole.device.mapper.TestCloudRequestMapper.Request;
import ffdd.opsconsole.shared.api.ApiResult;
import java.time.*;
import java.util.*;
import java.util.concurrent.atomic.*;
import org.junit.jupiter.api.*;
import org.springframework.transaction.*;
import org.springframework.transaction.support.*;

class TestCloudRequestsTest {
    final TestCloudRequestMapper mapper=mock(TestCloudRequestMapper.class);
    final AtomicReference<Request> stored=new AtomicReference<>();
    final AtomicBoolean commitFailure=new AtomicBoolean();
    final AbstractPlatformTransactionManager manager=new AbstractPlatformTransactionManager() {
        protected Object doGetTransaction() { return new Object(); }
        protected void doBegin(Object tx,TransactionDefinition definition) {
            assertThat(definition.getIsolationLevel()).isEqualTo(TransactionDefinition.ISOLATION_READ_COMMITTED);
        }
        protected void doCommit(DefaultTransactionStatus status) {
            if(commitFailure.getAndSet(false)) throw new TransactionSystemException("AMBIGUOUS_COMMIT");
        }
        protected void doRollback(DefaultTransactionStatus status) { }
    };
    final TestCloudRequests requests=new TestCloudRequests(mapper,new TestCloudDatabaseGate(2),
            Clock.fixed(TestCloudExecutorServiceTest.INSTANT,ZoneOffset.UTC),manager);
    final TestCloudExecutorService.Identity identity=new TestCloudExecutorService.Identity("cloud-unit",7,11,"NEX-UNIT-CLOUD","EM-UNIT","bge-m3:567m",TestCloudExecutorServiceTest.DIGEST);
    static final String HASH="a".repeat(64),KEY="complete-durable-unit";
    @BeforeEach void setup() {
        when(mapper.insertIntent(any())).thenAnswer(call->stored.compareAndSet(null,call.getArgument(0))?1:0);
        when(mapper.lock(anyString(),anyString(),anyString())).thenAnswer(call->stored.get());
        when(mapper.read(anyString(),anyString(),anyString())).thenAnswer(call->stored.get());
        when(mapper.commit(any(),any(),anyString(),any())).thenReturn(1);
        when(mapper.validateProof(any())).thenAnswer(call->{
            Request r=stored.get(); stored.set(new Request(r.executorId(),r.operation(),r.keyHash(),r.bodyHash(),r.ownerId(),r.deviceId(),
                    r.instanceNo(),r.taskConfigId(),r.taskNo(),r.state(),r.generation(),r.recoveryToken(),r.recoveryUsed(),r.proofTimestamp(),true,r.reviewedTermination(),r.responseJson(),r.updatedAt())); return 1;
        });
        when(mapper.outcome(any(),anyString(),nullable(String.class),any())).thenAnswer(call->{
            Request current=stored.get(),ticket=call.getArgument(0);
            if(!"PROCESSING".equals(current.state()) || current.generation()!=ticket.generation()) return 0;
            stored.set(copy(current,call.getArgument(1),current.generation(),call.getArgument(2),current.recoveryUsed())); return 1;
        });
        when(mapper.recover(any(),anyString(),any())).thenAnswer(call->{
            Request current=stored.get();
            if(current.recoveryUsed()) return 0;
            stored.set(copy(current,"PROCESSING",current.generation()+1,current.recoveryToken(),true)); return 1;
        });
    }
    Request copy(Request r,String state,long generation,String token,boolean used) {
        return new Request(r.executorId(),r.operation(),r.keyHash(),r.bodyHash(),r.ownerId(),r.deviceId(),r.instanceNo(),
                r.taskConfigId(),r.taskNo(),state,generation,token,used,r.proofTimestamp(),r.proofValidated(),r.reviewedTermination(),r.responseJson(),r.updatedAt());
    }
    ApiResult<Map<String,Object>> fail(String token,Long generation) {
        return requests.execute(identity,"COMPLETE",KEY,HASH,"CTA-UNIT",1L,token,generation,
                ticket->{ requests.authorizeProof(ticket); return requests.commit(ticket,()->{ throw new IllegalStateException("ACTUAL_ROLLBACK"); }); });
    }
    @Test void confirmedRollbackOffersExactlyOneRecoveryAndFencesOldGeneration() {
        assertThatThrownBy(()->fail(null,null)).hasMessage("ACTUAL_ROLLBACK");
        Request first=stored.get(); assertThat(first.state()).isEqualTo("ROLLED_BACK"); assertThat(first.recoveryToken()).hasSize(64);
        assertThatThrownBy(()->fail(null,null)).hasMessage("TEST_CLOUD_REQUEST_ROLLED_BACK");
        assertThatThrownBy(()->fail(first.recoveryToken(),1L)).hasMessage("ACTUAL_ROLLBACK");
        assertThat(stored.get().generation()).isEqualTo(2); assertThat(stored.get().recoveryUsed()).isTrue();
        assertThat(stored.get().recoveryToken()).hasSize(64);
        assertThatThrownBy(()->fail(first.recoveryToken(),1L)).hasMessage("TEST_CLOUD_REQUEST_ROLLED_BACK");
        assertThatThrownBy(()->requests.commit(first,()->ApiResult.ok(Map.of()))).hasMessage("TEST_CLOUD_REQUEST_FENCED");
    }
    @Test void ambiguousCommitNeverAuthorizesRollbackRecovery() {
        assertThatThrownBy(()->requests.execute(identity,"COMPLETE",KEY,HASH,"CTA-UNIT",1L,null,null,ticket->
                requests.commit(ticket,()->{ commitFailure.set(true); return ApiResult.ok(Map.of("taskNo","CTA-UNIT")); })))
                .hasMessage("AMBIGUOUS_COMMIT");
        assertThat(stored.get().state()).isEqualTo("UNKNOWN"); assertThat(stored.get().recoveryToken()).isNull();
        verify(mapper,never()).outcome(any(),eq("ROLLED_BACK"),any(),any());
    }
    @Test void processCrashOrMissingRecordNeverOffersRecoveryAndHashMismatchCannotBind() {
        assertThat(requests.view(null,"COMPLETE",KEY,HASH)).containsEntry("state","NOT_FOUND").containsEntry("recoveryAllowed",false);
        requests.execute(identity,"COMPLETE",KEY,HASH,"CTA-UNIT",1L,null,null,ticket->ApiResult.ok(Map.of()));
        assertThat(requests.view(stored.get(),"COMPLETE",KEY,HASH)).containsEntry("state","PROCESSING").containsEntry("recoveryAllowed",false);
        assertThatThrownBy(()->fail(null,null)).hasMessage("TEST_CLOUD_REQUEST_PROCESSING");
        assertThatThrownBy(()->requests.read(identity,"COMPLETE",KEY,"b".repeat(64),"CTA-UNIT"))
                .hasMessage("TEST_CLOUD_REQUEST_BINDING_CONFLICT");
    }
    @Test void ambientTransactionIsRejectedInsteadOfSuspendingItsBorrowedConnection() {
        var outer=new TransactionTemplate(manager); outer.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);
        assertThatThrownBy(()->outer.execute(status->requests.outside(()->"unsafe")))
                .hasMessage("TEST_CLOUD_OUTER_TRANSACTION");
        verifyNoInteractions(mapper);
    }
    @Test void invalidProofCannotManufactureAnExpiryTerminationToken() {
        assertThatThrownBy(()->requests.execute(identity,"COMPLETE",KEY,HASH,"CTA-UNIT",1L,null,null,ticket->{
            throw new IllegalArgumentException("INVALID_PROOF");
        })).hasMessage("INVALID_PROOF");
        assertThat(stored.get().state()).isEqualTo("ROLLED_BACK"); assertThat(stored.get().proofValidated()).isFalse();
        assertThat(stored.get().recoveryToken()).isNull();
    }
    @Test void legacyReviewBudgetConsumptionDoesNotValidateAnInvalidFreshProof() {
        when(mapper.markStabilityAttempt(anyString(),anyString())).thenAnswer(call->{
            Request r=stored.get(); stored.set(copy(r,"PROCESSING",1,null,true)); return 1;
        });
        assertThatThrownBy(()->requests.execute(identity,"COMPLETE",KEY,HASH,"CTA-UNIT",0L,null,null,ticket->{
            throw new IllegalArgumentException("WRONG_CLAIM_NONCE_AND_TIMESTAMP");
        })).hasMessage("WRONG_CLAIM_NONCE_AND_TIMESTAMP");
        assertThat(stored.get().recoveryUsed()).isTrue(); assertThat(stored.get().proofValidated()).isFalse();
        assertThat(stored.get().recoveryToken()).isNull();
        assertThatThrownBy(()->requests.terminate(identity,KEY,HASH,"CTA-UNIT","invented",1,r->Map.of()))
                .hasMessage("TEST_CLOUD_TERMINATION_NOT_AUTHORIZED");
    }
    @Test void independentTerminalFenceIsDurableAndIdempotentAfterCompletionBudgetWasSpent() {
        assertThatThrownBy(()->fail(null,null)).hasMessage("ACTUAL_ROLLBACK");
        var first=stored.get(); assertThatThrownBy(()->fail(first.recoveryToken(),1L)).hasMessage("ACTUAL_ROLLBACK");
        var exhausted=stored.get();
        when(mapper.terminate(any(),anyString(),anyString(),any())).thenAnswer(call->{
            Request r=stored.get();
            stored.set(new Request(r.executorId(),r.operation(),r.keyHash(),r.bodyHash(),r.ownerId(),r.deviceId(),r.instanceNo(),
                    r.taskConfigId(),r.taskNo(),r.state(),r.generation()+1,r.recoveryToken(),true,r.proofTimestamp(),true,r.reviewedTermination(),call.getArgument(2),r.updatedAt()));
            return 1;
        });
        var changes=new AtomicInteger();
        java.util.function.Function<Request,Map<String,Object>> terminate=r->{ changes.incrementAndGet(); return Map.of("taskStatus","CANCELLED"); };
        assertThat(requests.terminate(identity,KEY,HASH,"CTA-UNIT",exhausted.recoveryToken(),2,terminate).getData()).containsEntry("taskStatus","CANCELLED");
        assertThat(requests.terminate(identity,KEY,HASH,"CTA-UNIT",exhausted.recoveryToken(),2,terminate).getData()).containsEntry("taskStatus","CANCELLED");
        assertThat(changes).hasValue(1); assertThat(stored.get().generation()).isEqualTo(3);
        assertThatThrownBy(()->requests.terminate(identity,KEY,HASH,"CTA-UNIT",exhausted.recoveryToken(),3,terminate))
                .hasMessage("TEST_CLOUD_REQUEST_FENCED");
        assertThatThrownBy(()->fail(exhausted.recoveryToken(),2L)).hasMessage("TEST_CLOUD_REQUEST_ROLLED_BACK");
    }
}
