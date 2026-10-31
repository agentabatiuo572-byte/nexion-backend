package ffdd.opsconsole.device.application;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import ffdd.opsconsole.device.application.TestCloudExecutorService.Identity;
import ffdd.opsconsole.device.mapper.TestCloudRequestMapper;
import ffdd.opsconsole.device.mapper.TestCloudRequestMapper.Request;
import ffdd.opsconsole.shared.api.ApiResult;
import ffdd.opsconsole.shared.exception.BizException;
import java.time.*;
import java.util.*;
import java.util.function.*;
import org.springframework.stereotype.Component;
import org.springframework.transaction.*;
import org.springframework.transaction.support.*;

/** Durable intent is separate from the atomic business commit; no timeout authorizes replay. */
@Component
@lombok.extern.slf4j.Slf4j
public class TestCloudRequests {
    private final TestCloudRequestMapper mapper;
    private final TestCloudDatabaseGate gate;
    private final Clock clock;
    private final TransactionTemplate shortTransaction;
    private final TransactionTemplate noTransaction;
    private static final ObjectMapper JSON=new ObjectMapper();
    private final ThreadLocal<Boolean> businessStarted=ThreadLocal.withInitial(()->false);

    public TestCloudRequests(TestCloudRequestMapper mapper,TestCloudDatabaseGate gate,Clock clock,PlatformTransactionManager manager) {
        this.mapper=mapper; this.gate=gate; this.clock=clock;
        shortTransaction=new TransactionTemplate(manager);
        shortTransaction.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        shortTransaction.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);
        noTransaction=new TransactionTemplate(manager);
        noTransaction.setPropagationBehavior(TransactionDefinition.PROPAGATION_NOT_SUPPORTED);
    }
    public <T> T outside(Supplier<T> work) {
        // Suspending an outer transaction would retain its borrowed connection throughout model HTTP.
        if(TransactionSynchronizationManager.isActualTransactionActive()) throw new IllegalStateException("TEST_CLOUD_OUTER_TRANSACTION");
        return noTransaction.execute(status->work.get());
    }
    public <T> T db(Supplier<T> work) {
        if(TransactionSynchronizationManager.isActualTransactionActive()) throw new IllegalStateException("TEST_CLOUD_OUTER_TRANSACTION");
        return gate.call(()->shortTransaction.execute(status->work.get()));
    }
    public ApiResult<Map<String,Object>> execute(Identity i,String operation,String key,String hash,String taskNo,
            Long proofTimestamp,String recoveryToken,Long generation,Function<Request,ApiResult<Map<String,Object>>> work) {
        return outside(()->{
            var ticket=db(()->reserve(i,operation,key,hash,taskNo,proofTimestamp,recoveryToken,generation));
            if("COMMITTED".equals(ticket.state())) return ApiResult.ok(decode(ticket.responseJson()));
            businessStarted.set(false);
            try { return work.apply(ticket); }
            catch(RuntimeException | Error failed) {
                // A model/precheck failure happened outside a business transaction. No business transaction can
                // subsequently use this generation once the outcome CAS succeeds. A crashed process remains UNKNOWN.
                if(!businessStarted.get()) outcome(ticket,"ROLLED_BACK");
                throw failed;
            } finally { businessStarted.remove(); }
        });
    }
    private Request reserve(Identity i,String operation,String key,String hash,String taskNo,Long proofTimestamp,String token,Long generation) {
        validate(operation,key,hash);
        var candidate=new Request(i.executorId(),operation,TestCloudExecutorService.sha256(key),hash,i.ownerId(),i.deviceId(),
                i.instanceNo(),i.taskConfigId(),taskNo,"PROCESSING",1,null,false,proofTimestamp,false,false,null,now());
        if(mapper.insertIntent(candidate)==1) {
            if("COMPLETE".equals(operation) && mapper.markStabilityAttempt(i.executorId(),candidate.keyHash())>0)
                return mapper.lock(i.executorId(),operation,candidate.keyHash());
            return candidate;
        }
        var existing=mapper.lock(i.executorId(),operation,candidate.keyHash());
        requireBound(i,existing,hash,taskNo);
        if("COMMITTED".equals(existing.state())) return existing;
        if("ROLLED_BACK".equals(existing.state()) && !existing.recoveryUsed() && token!=null && existing.recoveryToken()!=null
                && Objects.equals(existing.recoveryToken(),token) && generation!=null && generation==existing.generation()
                && mapper.recover(existing,token,now())==1) {
            return mapper.lock(i.executorId(),operation,candidate.keyHash());
        }
        throw new BizException(409,"TEST_CLOUD_REQUEST_"+existing.state());
    }
    public void authorizeProof(Request ticket) {
        db(()->{
            if(mapper.validateProof(ticket)!=1) throw new BizException(409,"TEST_CLOUD_REQUEST_FENCED");
            return null;
        });
    }
    java.util.List<TestCloudRequestMapper.LegacyReview> stabilityReviews(String taskNo,long ownerId) { return mapper.stabilityReviews(taskNo,ownerId); }
    boolean durableNext(Identity i,String taskNo) { return mapper.durableNext(i.executorId(),taskNo)>0; }
    boolean stabilityUsed(String taskNo) { return mapper.stabilityUsed(taskNo)>0; }
    void consumeStabilityReview(Identity i,String taskNo,String key,String bodyHash,TestCloudRequestMapper.LegacyReview review,String originalHash,boolean terminalAuthority) {
        if(mapper.useStabilityReview(review.id(),taskNo,i.executorId(),TestCloudExecutorService.sha256(key),originalHash,bodyHash,terminalAuthority,now())!=1
                && mapper.sameStabilityUse(review.id(),taskNo,i.executorId(),TestCloudExecutorService.sha256(key),originalHash,bodyHash)!=1)
            throw new BizException(409,"TEST_CLOUD_STABILITY_BUDGET_EXHAUSTED");
    }
    public ApiResult<Map<String,Object>> commit(Request ticket,Supplier<ApiResult<Map<String,Object>>> work) {
        businessStarted.set(true);
        var completion=new java.util.concurrent.atomic.AtomicInteger(TransactionSynchronization.STATUS_UNKNOWN);
        try { return db(()->{
            var current=mapper.lock(ticket.executorId(),ticket.operation(),ticket.keyHash());
            if(current==null || !"PROCESSING".equals(current.state()) || current.generation()!=ticket.generation()
                    || !current.bodyHash().equals(ticket.bodyHash())) throw new BizException(409,"TEST_CLOUD_REQUEST_FENCED");
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                public void afterCompletion(int status) {
                    completion.set(status);
                }
            });
            var result=work.get();
            String taskNo=Objects.toString(result.getData().get("taskNo"),ticket.taskNo());
            if(mapper.commit(ticket,taskNo,encode(result.getData()),now())!=1) throw new BizException(409,"TEST_CLOUD_REQUEST_FENCED");
            return result;
        }); } finally {
            // Cleanup and permit release precede the separate durable outcome transaction.
            if(completion.get()==TransactionSynchronization.STATUS_ROLLED_BACK) outcome(ticket,"ROLLED_BACK");
            else if(completion.get()==TransactionSynchronization.STATUS_UNKNOWN) outcome(ticket,"UNKNOWN");
        }
    }
    private void outcome(Request ticket,String state) {
        try {
            // The completed business transaction and its admission permit have already been released.
            outside(()->db(()->{
                Request current=mapper.lock(ticket.executorId(),ticket.operation(),ticket.keyHash());
                boolean authorized=current!=null && ("NEXT".equals(current.operation()) || current.proofValidated() || current.reviewedTermination());
                return mapper.outcome(ticket,state,"ROLLED_BACK".equals(state) && authorized
                        ? UUID.randomUUID().toString().replace("-","")+UUID.randomUUID().toString().replace("-","") : null,now());
            }));
        } catch(RuntimeException unavailable) {
            // Preserve PROCESSING/UNKNOWN on an unavailable outcome store. Absence is never retry authority.
            log.warn("TEST_CLOUD_REQUEST_OUTCOME_UNAVAILABLE executor={} operation={} keyHash={} bodyHash={} generation={} exception={}",
                    ticket.executorId(),ticket.operation(),ticket.keyHash(),ticket.bodyHash(),ticket.generation(),unavailable.getClass().getSimpleName());
        }
    }
    public Request read(Identity i,String operation,String key,String hash,String taskNo) {
        validate(operation,key,hash);
        var value=mapper.read(i.executorId(),operation,TestCloudExecutorService.sha256(key));
        if(value!=null) requireBound(i,value,hash,taskNo);
        return value;
    }
    public Map<String,Object> view(Request value,String operation,String key,String hash) {
        Map<String,Object> result=new LinkedHashMap<>();
        result.put("operation",operation); result.put("requestKeyHash",TestCloudExecutorService.sha256(key)); result.put("requestHash",hash);
        String state=value==null?"NOT_FOUND":value.state();
        // Slow or crashed work is quarantined, never adopted by another generation.
        if(value!=null && "PROCESSING".equals(state) && value.updatedAt().isBefore(now().minusMinutes(10))) state="UNKNOWN";
        result.put("state",state); result.put("executionGeneration",value==null?null:value.generation());
        result.put("taskNo",value==null?null:value.taskNo());
        boolean recover=value!=null && "ROLLED_BACK".equals(state) && !value.recoveryUsed() && value.recoveryToken()!=null;
        result.put("recoveryAllowed",recover); result.put("maxAttempts",1);
        result.put("recoveryToken",value!=null && "ROLLED_BACK".equals(state)?value.recoveryToken():null);
        result.put("response",value!=null && "COMMITTED".equals(state)?decode(value.responseJson()):null);
        return result;
    }
    public ApiResult<Map<String,Object>> terminate(Identity i,String key,String hash,String taskNo,String token,long generation,
            Function<Request,Map<String,Object>> terminate) {
        return outside(()->db(()->{
            validate("COMPLETE",key,hash);
            var value=mapper.lock(i.executorId(),"COMPLETE",TestCloudExecutorService.sha256(key));
            requireBound(i,value,hash,taskNo);
            if(!"ROLLED_BACK".equals(value.state()) || token==null || value.recoveryToken()==null || !(value.proofValidated() || value.reviewedTermination())
                    || !Objects.equals(token,value.recoveryToken()))
                throw new BizException(409,"TEST_CLOUD_TERMINATION_NOT_AUTHORIZED");
            if(value.recoveryUsed() && value.generation()==generation+1 && value.responseJson()!=null)
                return ApiResult.ok(decode(value.responseJson()));
            if(value.responseJson()!=null) throw new BizException(409,"TEST_CLOUD_REQUEST_FENCED");
            if(value.generation()!=generation) throw new BizException(409,"TEST_CLOUD_REQUEST_FENCED");
            var result=terminate.apply(value);
            if(mapper.terminate(value,token,encode(result),now())!=1) throw new BizException(409,"TEST_CLOUD_REQUEST_FENCED");
            return ApiResult.ok(result);
        }));
    }
    private void requireBound(Identity i,Request r,String hash,String taskNo) {
        if(r==null || !i.executorId().equals(r.executorId()) || i.ownerId()!=r.ownerId() || i.deviceId()!=r.deviceId()
                || !i.instanceNo().equals(r.instanceNo()) || !i.taskConfigId().equals(r.taskConfigId())
                || !hash.equals(r.bodyHash()) || ("COMPLETE".equals(r.operation()) && !Objects.equals(taskNo,r.taskNo())))
            throw new BizException(409,"TEST_CLOUD_REQUEST_BINDING_CONFLICT");
    }
    private void validate(String operation,String key,String hash) {
        if(!Set.of("NEXT","COMPLETE").contains(operation) || key==null || !key.matches("[A-Za-z0-9._:-]{8,128}")
                || hash==null || !hash.matches("[a-f0-9]{64}")) throw new BizException(422,"TEST_CLOUD_REQUEST_INVALID");
    }
    private LocalDateTime now() { return LocalDateTime.now(clock); }
    static String encode(Map<String,Object> value) { try { return JSON.writeValueAsString(value); } catch(Exception bad) { throw new IllegalStateException(bad); } }
    static Map<String,Object> decode(String value) { try { return JSON.readValue(value,new TypeReference<>() { }); } catch(Exception bad) { throw new IllegalStateException(bad); } }
}
