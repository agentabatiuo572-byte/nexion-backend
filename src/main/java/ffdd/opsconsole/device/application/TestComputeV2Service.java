package ffdd.opsconsole.device.application;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import ffdd.opsconsole.device.application.TestCloudExecutorService.Identity;
import ffdd.opsconsole.device.compute.v2.*;
import ffdd.opsconsole.device.compute.v2.ComputeV2Event.*;
import ffdd.opsconsole.device.domain.TaskSchedulingSettings;
import ffdd.opsconsole.device.mapper.AppTaskAssignmentMapper;
import ffdd.opsconsole.device.mapper.TaskSchedulingPolicyMapper;
import ffdd.opsconsole.device.mapper.ComputeV2AdmissionMapper;
import ffdd.opsconsole.shared.capacity.E3DeviceCapacityPolicy;
import ffdd.opsconsole.shared.config.DateTimeFormatConfig;
import ffdd.opsconsole.shared.exception.BizException;
import ffdd.opsconsole.shared.storage.ObjectStorageService;
import java.io.ByteArrayInputStream;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.Semaphore;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicInteger;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.core.env.Environment;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.core.Authentication;

/** TEST-only bridge. DB authority is short-lived; inference/MinIO/MQ never hold a SQL connection. */
@Service
@ConditionalOnProperty(name="nexion.compute-v2.enabled",havingValue="true")
public class TestComputeV2Service {
    private static final Logger LOG=LoggerFactory.getLogger(TestComputeV2Service.class);
    private final ComputeV2Runtime runtime;
    private final TestCloudExecutorService cloud;
    private final TestCloudRequests transactions;
    private final AppTaskAssignmentMapper assignments;
    private final TaskSchedulingPolicyMapper policies;
    private final ObjectStorageService objects;
    private final ComputeV2AdmissionMapper admission;
    private final TestCloudActivationAdmission physicalAdmission;
    private final Environment environment;
    private final ObjectMapper json;
    private final ComputeV2ModalRegistry modalRegistry;
    private final ComputeV2ModalVerifier modalVerifier;
    private final Optional<PhoneComputeV2Access> phones;
    private final Clock clock;
    private final Set<Long> enrolled;
    private final Set<String> allowedTasks;
    private final byte[] proofSecret;
    private final Map<String,String> runnerContainers;
    @SuppressWarnings("ArchitectureConfigField") // Validated from constructor-injected deployment configuration.
    private final String validatorContainer;
    private final String sharedBgeContainer,modalContainer;
    private final Set<String> activeValidations=ConcurrentHashMap.newKeySet();
    private final Queue<Long> readyDevices=new ConcurrentLinkedQueue<>();
    private final Set<Long> readyDeviceIds=ConcurrentHashMap.newKeySet();
    private final SecureRandom random=new SecureRandom();
    private final Semaphore validator=new Semaphore(1,true),validationCapacity=new Semaphore(13,true);
    private final Optional<ComputeV2DeviceLifecycleService> lifecycle;
    private final AtomicInteger scanCursor=new AtomicInteger();

    public TestComputeV2Service(ComputeV2Runtime runtime,TestCloudExecutorService cloud,TestCloudRequests transactions,
            AppTaskAssignmentMapper assignments,TaskSchedulingPolicyMapper policies,ObjectStorageService objects,
            ComputeV2AdmissionMapper admission,Environment environment,ObjectMapper json,Clock clock,
            Optional<ComputeV2DeviceLifecycleService> lifecycle,TestCloudActivationAdmission physicalAdmission) {
        this(runtime,cloud,transactions,assignments,policies,objects,admission,environment,json,clock,lifecycle,physicalAdmission,Optional.empty());
    }
    @Autowired
    public TestComputeV2Service(ComputeV2Runtime runtime,TestCloudExecutorService cloud,TestCloudRequests transactions,
            AppTaskAssignmentMapper assignments,TaskSchedulingPolicyMapper policies,ObjectStorageService objects,
            ComputeV2AdmissionMapper admission,Environment environment,ObjectMapper json,Clock clock,
            Optional<ComputeV2DeviceLifecycleService> lifecycle,TestCloudActivationAdmission physicalAdmission,Optional<PhoneComputeV2Access> phones) {
        this.runtime=runtime;this.cloud=cloud;this.transactions=transactions;this.assignments=assignments;
        this.policies=policies;this.objects=objects;this.admission=admission;this.lifecycle=lifecycle;this.physicalAdmission=physicalAdmission;
        this.environment=environment;this.json=json;this.clock=clock;
        this.modalRegistry=new ComputeV2ModalRegistry(environment,json);this.modalVerifier=new ComputeV2ModalVerifier(environment,json);
        this.phones=phones;
        if(!"TEST".equals(environment.getProperty("nexion.compute-v2.deployment-scope")))
            throw new IllegalStateException("COMPUTE_V2_TEST_ADAPTER_SCOPE_REQUIRED");
        enrolled=ids(environment.getProperty("nexion.compute-v2.device-ids",""));
        allowedTasks=Set.copyOf(Arrays.stream(environment.getProperty("nexion.compute-v2.allowed-task-ids","").split(","))
                .map(String::trim).filter(s->!s.isEmpty()).toList());
        String secret=environment.getProperty("nexion.compute-v2.proof-secret","");
        if(!secret.matches("[a-f0-9]{64}") || allowedTasks.isEmpty())
            throw new IllegalStateException("COMPUTE_V2_EXECUTOR_CAPABILITY_CONFIGURATION_REQUIRED");
        proofSecret=HexFormat.of().parseHex(secret);
        var containers=new HashMap<String,String>();
        for(int n=1;n<=6;n++) {
            String id=environment.getProperty("nexion.compute-v2.runner-"+n+"-container-id","");
            if(!id.matches("[a-f0-9]{64}")) throw new IllegalStateException("COMPUTE_V2_RUNNER_CONTAINER_REQUIRED");
            containers.put("runner-"+n,id);
        }
        validatorContainer=environment.getProperty("nexion.compute-v2.validator-container-id","");
        if(!validatorContainer.matches("[a-f0-9]{64}") || new HashSet<>(containers.values()).size()!=6 || containers.containsValue(validatorContainer))
            throw new IllegalStateException("COMPUTE_V2_EXCLUSIVE_MODEL_CONTAINERS_REQUIRED");
        runnerContainers=Map.copyOf(containers);
        sharedBgeContainer=environment.getProperty("nexion.compute-v2.shared-bge-container-id","");
        modalContainer=environment.getProperty("nexion.compute-v2.modal-container-id","");
        if(!sharedBgeContainer.isEmpty() && (!sharedBgeContainer.matches("[a-f0-9]{64}") || sharedBgeContainer.equals(validatorContainer))
                || !modalContainer.isEmpty() && (!modalContainer.matches("[a-f0-9]{64}") || modalContainer.equals(validatorContainer)
                || modalContainer.equals(sharedBgeContainer)))throw new IllegalStateException("COMPUTE_V2_SHARED_MODEL_CONTAINERS_INVALID");
    }

    public record Complete(long generation,String claimToken,String proofNonce,String inputHash,String modelDigest,
            String resultHash,String resultArtifactBase64,long proofTimestamp) { }
    public static final int MAX_COMPLETION_BODY_BYTES=ComputeV2ModalVerifier.MAX_ENCODED_BUNDLE_BYTES+8192;
    record Manifest(int version,String runtimeKey,String validatorKey,String taskConfigId,long revision,
            String model,String modelDigest,int executionTimeoutSec,List<String> texts) { }
    record ManifestV3(int version,String taskClass,String runtimeKey,String validatorKey,String taskConfigId,long revision,
            String modelName,String modelRevision,String modelDigest,JsonNode workload,long seed,int executionTimeoutSec) { }
    record FrozenInput(int version,String runtimeKey,String validatorKey,String model,String modelRevision,
            String modelDigest,int executionTimeoutSec,List<String> texts,JsonNode workload,long seed) { }
    record Issuance(Identity identity,TaskSnapshot snapshot,byte[] manifest) { }

    public synchronized Map<String,Object> next(Identity caller,String runner) {
        if(org.springframework.transaction.support.TransactionSynchronizationManager.isActualTransactionActive())
            throw new IllegalStateException("COMPUTE_V2_DISPATCH_OUTER_TRANSACTION");
        requireFleet(caller);String executor=executor(runner);
        var original=runtime.forExecutor(executor);
        if(original.isPresent()) {
            var view=original.get();var event=view.event();
            if(Set.of("STARTING","COMPUTING","VERIFYING").contains(view.status()) && view.leaseExpiresAt()!=null
                    && !view.leaseExpiresAt().isAfter(clock.instant()) && !runtime.completionPending(event.taskNo(),event.generation())) {
                runtime.fenceExpired(event.userId(),event.deviceId(),event.generation());
                view=runtime.view(event.userId(),event.deviceId()).orElseThrow();
            }
            if("STARTING".equals(view.status())) {
                // A lost commit/send acknowledgement retries the immutable reservation without borrowing another permit.
                runtime.confirmExecution(view.event());
                view=runtime.view(event.userId(),event.deviceId()).orElseThrow();
            }
            return manifest(view);
        }
        if(!runtime.canAcceptWork())return Map.of("state","IDLE","retryAfterMs",1000);
        // Single global head is consumed fairly. Ineligible heads terminate without consuming a compute permit.
        for(var view:runtime.queuedCloud(12)) {
            var event=view.event();
            if(!"EM".equals(event.task().taskClass()) && !modalRegistry.ready(event.task().taskClass(),event.task().modelName(),
                    "sha256:"+event.task().modelSha256())) continue;
            Optional<ComputeV2Event> acquired;
            try { acquired=transactions.db(()->{
                // Serialize against legacy activation before checking ownership or reserving physical compute.
                physicalAdmission.lock();
                if(!physicalAdmission.canStartV2Execution(runtime.activeExecutionCount())) return Optional.empty();
                eligible(caller,event.deviceId(),event.userId(),false);
                return runtime.reserveExecution(event.userId(),event.deviceId(),executor);
            }); }
            catch(BizException invalid) {
                if(invalid.getCode()!=409 && invalid.getCode()!=403 && invalid.getCode()!=404) throw invalid;
                runtime.fail(event.userId(),event.deviceId(),event.generation(),"ADMISSION_REVOKED");
                runtime.retire(event.userId(),event.deviceId());continue;
            }
            if(acquired.isEmpty()) break;
            runtime.confirmExecution(acquired.get());
            return manifest(runtime.view(event.userId(),event.deviceId()).orElseThrow());
        }
        return Map.of("state","IDLE","retryAfterMs",1000);
    }

    public Map<String,Object> heartbeat(Identity caller,String runner,String taskNo,long generation,String token) {
        var view=ownedExecution(caller,runner,taskNo,generation,token);
        runtime.renew(view.event().userId(),view.event().deviceId(),generation,executionId(caller,runner));
        return Map.of("state",view.status(),"serverNow",clock.instant().toString());
    }

    public synchronized Map<String,Object> phoneNext(Authentication auth,PhoneComputeV2Access.EngineReady report) {
        PhoneComputeV2Access access=phones.orElseThrow(()->error(503,"PHONE_EXECUTOR_UNAVAILABLE"));
        Identity caller=transactions.db(()->{
            Identity current=access.current(auth);
            if(!enrolled.contains(current.deviceId()))throw error(403,"COMPUTE_V2_DEVICE_NOT_ENROLLED");
            access.loaded(current,report,modalRegistry.phoneRuntime().modelDigest());access.eligible(current,false);return current;
        });
        var current=runtime.view(caller.ownerId(),caller.deviceId());
        if(current.isPresent() && Set.of("COMPLETED","FAILED").contains(current.get().status()) && current.get().executorId()==null) {
            runtime.retire(caller.ownerId(),caller.deviceId());current=Optional.empty();
        }
        if(current.isEmpty()) {
            if(environment.getProperty("nexion.simulated-tasks.enabled",Boolean.class,false))
                return Map.of("state","IDLE","retryAfterMs",60000);
            if(!runtime.isReady() || !runtime.canAcceptWork())return Map.of("state","IDLE","retryAfterMs",1000);
            Issuance issuance=transactions.db(()->{access.requireCurrent(auth,caller);return freeze(caller.deviceId(),caller);});
            issue(issuance);current=runtime.view(caller.ownerId(),caller.deviceId());
        }
        var view=current.orElseThrow();
        requireOwnedTask(caller,view.event());
        if("ISSUING".equals(view.status())) {
            runtime.issue(caller.ownerId(),caller.deviceId(),caller.instanceNo(),view.event().task());
            view=runtime.view(caller.ownerId(),caller.deviceId()).orElseThrow();
        }
        if("QUEUED".equals(view.status())) {
            var acquired=transactions.db(()->{
                physicalAdmission.lock();access.requireCurrent(auth,caller);eligible(caller,caller.deviceId(),caller.ownerId(),false);
                return runtime.reserveExecution(caller.ownerId(),caller.deviceId(),caller.executorId());
            });
            if(acquired.isEmpty())return Map.of("state","IDLE","retryAfterMs",1000);
            runtime.confirmExecution(acquired.get());view=runtime.view(caller.ownerId(),caller.deviceId()).orElseThrow();
        } else if(view.executorId()!=null && !caller.executorId().equals(view.executorId()))throw error(409,"COMPUTE_V2_PHONE_OWNER_CHANGED");
        if("STARTING".equals(view.status())) {runtime.confirmExecution(view.event());view=runtime.view(caller.ownerId(),caller.deviceId()).orElseThrow();}
        if(Set.of("COMPUTING","VERIFYING").contains(view.status()) && view.leaseExpiresAt()!=null
                && !view.leaseExpiresAt().isAfter(clock.instant()) && !runtime.completionPending(view.event().taskNo(),view.event().generation())) {
            runtime.fenceExpired(caller.ownerId(),caller.deviceId(),view.event().generation());view=runtime.view(caller.ownerId(),caller.deviceId()).orElseThrow();
        }
        return manifest(view);
    }

    public Map<String,Object> phoneHeartbeat(Authentication auth,String taskNo,long generation,String claim) {
        Identity caller=phoneCaller(auth);ownedExecution(caller,null,taskNo,generation,claim);
        transactions.db(()->{phones.orElseThrow().requireCurrent(auth,caller);phones.orElseThrow().heartbeat(caller);
            eligible(caller,caller.deviceId(),caller.ownerId(),true);return null;});
        return heartbeat(caller,null,taskNo,generation,claim);
    }
    public Map<String,Object> phoneComplete(Authentication auth,String taskNo,Complete request) {
        return complete(phoneCaller(auth),null,taskNo,request,auth);
    }
    public Map<String,Object> phoneReceipt(Authentication auth,String taskNo,long generation,String claim) {
        return receipt(phoneCaller(auth),null,taskNo,generation,claim);
    }

    public Map<String,Object> phoneExited(Authentication auth,String taskNo,long generation,String claim,boolean workerExited,String reason) {
        if(!workerExited || !Set.of("COMPLETED","APP_PAUSED","PHONE_LOW_BATTERY","PHONE_OFFLINE","WORKER_ERROR").contains(reason))
            throw error(422,"COMPUTE_V2_PHONE_EXIT_EVIDENCE_INVALID");
        Identity caller=phoneCaller(auth);var event=retained(taskNo);requireOwnedTask(caller,event);
        if(event.generation()!=generation || !constant(claim,signature(event,"claim")))throw error(403,"COMPUTE_V2_PROOF_BINDING_INVALID");
        if(!activeValidations.add(taskNo))throw error(503,"COMPUTE_V2_VALIDATION_IN_PROGRESS");
        try {
            if(runtime.completionPending(taskNo,generation))throw error(503,"COMPUTE_V2_VALIDATION_IN_PROGRESS");
            if(runtime.hasExited(taskNo,generation) || admission.exitCount(taskNo,generation)>0) {
                phones.orElseThrow().exited(caller.deviceId());afterExit(event);return Map.of("taskNo",taskNo,"state","EXIT_CONFIRMED");
            }
            var view=ownedExecution(caller,null,taskNo,generation,claim);
            if(view.event().kind()==Kind.VERIFIED) {
                var result=exited(caller,null,taskNo,generation,claim);phones.orElseThrow().exited(caller.deviceId());return result;
            }
            if("COMPLETED".equals(reason))throw error(409,"COMPUTE_V2_EXIT_REQUIRES_VERIFIED_RESULT");
            if(Set.of("COMPUTING","VERIFYING","STARTING").contains(view.status()))
                runtime.fail(caller.ownerId(),caller.deviceId(),generation,reason);
            else if(!Set.of("FAILED","RECOVERING").contains(view.status()))throw error(409,"COMPUTE_V2_NOT_TERMINAL");
            byte[] evidence=encode(Map.of("taskNo",taskNo,"generation",generation,"ownerId",caller.ownerId(),"deviceId",caller.deviceId(),
                    "executor",caller.executorId(),"workerExited",true,"reason",reason,"observedAt",clock.instant().toString()));
            String ref="compute-v2/TEST/recovery/"+taskNo+"/"+TestCloudExecutorService.sha256(evidence)+".json";
            objects.put(ref,"application/json",new ByteArrayInputStream(evidence),evidence.length);
            runtime.executionObservedExit(caller.ownerId(),caller.deviceId(),generation,ref);
            phones.orElseThrow().exited(caller.deviceId());afterExit(event);
            return Map.of("taskNo",taskNo,"state","EXIT_CONFIRMED","evidenceRef",ref);
        } finally {activeValidations.remove(taskNo);}
    }

    private Identity phoneCaller(Authentication auth){return transactions.db(()->phones.orElseThrow(()->error(503,"PHONE_EXECUTOR_UNAVAILABLE")).current(auth));}

    public Map<String,Object> complete(Identity caller,String runner,String taskNo,Complete request) {
        return complete(caller,runner,taskNo,request,null);
    }
    private Map<String,Object> complete(Identity caller,String runner,String taskNo,Complete request,Authentication phoneAuth) {
        if(org.springframework.transaction.support.TransactionSynchronizationManager.isActualTransactionActive())
            throw new IllegalStateException("COMPUTE_V2_INFERENCE_OUTER_TRANSACTION");
        var view=ownedExecution(caller,runner,taskNo,request.generation(),request.claimToken());
        var event=view.event();
        if(event.kind()==Kind.VERIFIED) {
            if(!event.evidence().resultSha256().equals(request.resultHash()) || !event.task().inputSha256().equals(request.inputHash())
                    || !("sha256:"+event.task().modelSha256()).equals(request.modelDigest())
                    || !constant(request.proofNonce(),signature(event,"nonce"))) throw error(409,"COMPUTE_V2_PROOF_CONFLICT");
            return manifest(view);
        }
        if("VERIFYING".equals(view.status())) {
            validateProof(view,request,false);
            if(activeValidations.contains(taskNo) || runtime.completionPending(taskNo,request.generation()))
                throw error(503,"COMPUTE_V2_VALIDATION_IN_PROGRESS");
            throw error(409,"COMPUTE_V2_VALIDATION_RESTART_REQUIRES_RECOVERY");
        }
        validateProof(view,request,true);
        Instant completedAt=clock.instant();
        FrozenInput input=input(event);
        Map<String,Object> modalTask=input.version()==3?modalTask(event,input):null;
        ComputeV2ModalVerifier.Submission modalSubmission;
        if(input.version()==2) {
            TestCloudBgeArtifact.decode(request.resultArtifactBase64(),request.resultHash());modalSubmission=null;
        } else modalSubmission=modalVerifier.decode(request.resultArtifactBase64(),request.resultHash(),modalTask);
        String submission=TestCloudExecutorService.sha256(taskNo+":"+request.generation()+":"+request.resultHash());
        if(!validationCapacity.tryAcquire()) throw error(429,"COMPUTE_V2_VALIDATION_QUEUE_FULL");
        boolean acquired=false,verifying=false,validationRegistered=false;
        try {
            validationRegistered=activeValidations.add(taskNo);
            if(!validationRegistered)throw error(503,"COMPUTE_V2_VALIDATION_IN_PROGRESS");
            if(!runtime.beginVerification(event.userId(),event.deviceId(),event.generation(),submission))
                throw error(503,"COMPUTE_V2_VALIDATION_IN_PROGRESS");
            verifying=true;
            validator.acquire();acquired=true;
            validateProof(ownedExecution(caller,runner,taskNo,request.generation(),request.claimToken()),request,false);
            byte[] bytes;
            if(input.version()==2) {
                Identity device=cloud.computeV2Identity(caller,event.deviceId());
                bytes=TestCloudBgeArtifact.verify(request.resultArtifactBase64(),request.resultHash(),cloud.infer(device,input.texts()));
            } else bytes=modalVerifier.verify(modalSubmission,modalTask,request.resultHash(),input.executionTimeoutSec());
            // Recheck mutable ownership after expensive proof verification; never redraw the task quote.
            long nex=transactions.db(()->{
                if(phoneAuth!=null)phones.orElseThrow().requireCurrent(phoneAuth,caller);
                eligible(caller,event.deviceId(),event.userId(),true);
                var wallet=wallet(event.userId());
                if(((Number)wallet.get("id")).longValue()!=event.task().walletId()
                        || !event.task().walletIncarnation().equals(wallet.get("incarnation")))
                    throw error(409,"COMPUTE_V2_WALLET_IDENTITY_CHANGED");
                if(PhoneComputeV2Access.isPhone(caller))return 0L;
                BigDecimal amount=assignments.lockPaidCloudShareDailyNex(event.userId(),event.deviceId());
                String dailyKey="CLOUD_SHARE_DAILY:"+event.deviceId()+":"+completedAt.atZone(ZoneOffset.UTC).toLocalDate();
                if(assignments.lockDailyCloudShareNex(event.userId(),dailyKey)!=null) return 0L;
                return amount==null?0L:ComputeV2Selection.micros(amount);
            });
            var current=ownedExecution(caller,runner,taskNo,event.generation(),request.claimToken());
            if(current.leaseExpiresAt()==null || !current.leaseExpiresAt().isAfter(clock.instant()))
                throw error(409,"COMPUTE_V2_LEASE_EXPIRED");
            Instant completed=completedAt;
            String artifact="compute-v2/TEST/result/"+event.userId()+"/"+taskNo+"/"+request.resultHash()+(input.version()==2?".f32":".bundle.json");
            objects.put(artifact,input.version()==2?"application/octet-stream":"application/json",new ByteArrayInputStream(bytes),bytes.length);
            LocalDate day=completed.atZone(ZoneOffset.UTC).toLocalDate();
            String proof=TestCloudExecutorService.sha256(taskNo+":"+event.generation()+":"+event.task().inputSha256()+":"+request.resultHash()+":"+request.proofNonce());
            runtime.verifiedComplete(event.userId(),event.deviceId(),event.generation(),new Verified(proof,
                    event.task().inputSha256(),request.resultHash(),clock.instant(),completed,artifact,bytes.length,nex,
                    nex>0?"CLOUD_SHARE_DAILY:"+event.deviceId()+":"+day:null,day));
            return manifest(runtime.view(event.userId(),event.deviceId()).orElseThrow());
        } catch(InterruptedException interrupted) {
            Thread.currentThread().interrupt();throw error(503,"COMPUTE_V2_VALIDATION_INTERRUPTED");
        } catch(RuntimeException failure) {
            // Unknown MQ send already has a staged immutable VERIFIED event; fencing must not discard it.
            if(verifying && failure instanceof BizException)
                runtime.failVerification(event.userId(),event.deviceId(),event.generation(),submission,"VALIDATION_REQUIRES_RECOVERY");
            throw failure;
        } finally {
            if(validationRegistered) activeValidations.remove(taskNo);
            if(acquired) validator.release();validationCapacity.release();
        }
    }

    /** Root observer signs physical exit evidence; workers have claim tokens but never the signing key. */
    public Map<String,Object> observedExit(Identity caller,String runner,String taskNo,long generation,String claim,
            JsonNode observation,String signed) {
        requireFleet(caller);executor(runner);
        Set<String> expected=Set.of("taskNo","generation","runner","observedAt","computeContainerId","validatorContainerId","computeExitCode","validatorExitCode");
        Set<String> keys=new HashSet<>();observation.fieldNames().forEachRemaining(keys::add);
        if(!observation.isObject() || !keys.equals(expected)) throw error(422,"COMPUTE_V2_EXIT_EVIDENCE_INVALID");
        var values=new TreeMap<String,Object>();observation.fields().forEachRemaining(field->{
            JsonNode value=field.getValue();if(value.isTextual())values.put(field.getKey(),value.textValue());
            else if(value.isIntegralNumber()&&value.canConvertToLong())values.put(field.getKey(),value.longValue());
            else throw error(422,"COMPUTE_V2_EXIT_EVIDENCE_INVALID");});
        byte[] bytes=encode(values);
        if(!constant(signed,hmac(bytes)) || !taskNo.equals(observation.path("taskNo").asText())
                || generation!=observation.path("generation").asLong(-1) || !runner.equals(observation.path("runner").asText())
                || !observation.path("computeExitCode").isInt() || !observation.path("validatorExitCode").isInt())
            throw error(403,"COMPUTE_V2_EXIT_EVIDENCE_INVALID");
        var event=retained(taskNo);
        var frozen=input(event);
        if(isPhoneTask(event) || !computeContainer(frozen,runner).equals(observation.path("computeContainerId").asText())
                || !validationContainer(frozen).equals(observation.path("validatorContainerId").asText()))
            throw error(403,"COMPUTE_V2_EXIT_EVIDENCE_INVALID");
        if(event.generation()!=generation || !constant(claim,signature(event,"claim"))) throw error(403,"COMPUTE_V2_PROOF_BINDING_INVALID");
        Instant observed;
        try{observed=Instant.parse(observation.path("observedAt").textValue());}catch(RuntimeException malformed){throw error(422,"COMPUTE_V2_EXIT_EVIDENCE_INVALID");}
        if(event.task().startedAt()==null || observed.isBefore(event.task().startedAt())
                || observed.isAfter(clock.instant().plusSeconds(5)) || observed.isBefore(clock.instant().minusSeconds(300)))
            throw error(409,"COMPUTE_V2_EXIT_EVIDENCE_STALE");
        if(runtime.hasExited(taskNo,generation)){afterExit(event);return Map.of("taskNo",taskNo,"state","EXIT_CONFIRMED");}
        var view=runtime.view(event.userId(),event.deviceId()).orElseThrow(()->error(409,"COMPUTE_V2_GENERATION_NOT_CURRENT"));
        if(!view.event().taskNo().equals(taskNo) || activeValidations.contains(taskNo)) throw error(409,"COMPUTE_V2_VALIDATOR_STILL_ACTIVE");
        if(Set.of("COMPUTING","VERIFYING","STARTING").contains(view.status()))
            runtime.fenceExpired(event.userId(),event.deviceId(),generation);
        else if(!Set.of("FAILED","RECOVERING","COMPLETED").contains(view.status())) throw error(409,"COMPUTE_V2_NOT_TERMINAL");
        String ref="compute-v2/TEST/recovery/"+taskNo+"/"+TestCloudExecutorService.sha256(bytes)+".json";
        objects.put(ref,"application/json",new ByteArrayInputStream(bytes),bytes.length);
        runtime.executionObservedExit(event.userId(),event.deviceId(),generation,ref);
        afterExit(event);
        return Map.of("taskNo",taskNo,"state","EXIT_CONFIRMED","evidenceRef",ref);
    }

    /** The bounded worker calls this only after both successful inference responses and durable VERIFIED acknowledgement. */
    public Map<String,Object> exited(Identity caller,String runner,String taskNo,long generation,String token) {
        requireExecutor(caller);executionId(caller,runner);
        var retained=retained(taskNo);
        requireOwnedTask(caller,retained);
        if(retained.generation()!=generation || !constant(token,signature(retained,"claim"))) throw error(403,"COMPUTE_V2_PROOF_BINDING_INVALID");
        if(runtime.hasExited(taskNo,generation) || admission.exitCount(taskNo,generation)>0){
            afterExit(retained);return Map.of("taskNo",taskNo,"state","EXIT_CONFIRMED");}
        var view=ownedExecution(caller,runner,taskNo,generation,token);
        if(view.event().kind()!=Kind.VERIFIED) throw error(409,"COMPUTE_V2_EXIT_REQUIRES_VERIFIED_RESULT");
        runtime.executionExited(view.event().userId(),view.event().deviceId(),generation,executionId(caller,runner));
        afterExit(view.event());
        return Map.of("taskNo",taskNo,"state","EXIT_CONFIRMED");
    }

    public Map<String,Object> receipt(Identity caller,String runner,String taskNo,long generation,String token) {
        requireExecutor(caller);executionId(caller,runner);
        var event=retained(taskNo);
        requireOwnedTask(caller,event);
        if(event.generation()!=generation || !constant(token,signature(event,"claim"))) throw error(403,"COMPUTE_V2_PROOF_BINDING_INVALID");
        Map<String,Object> result=new LinkedHashMap<>();result.put("taskNo",taskNo);
        result.put("generation",event.generation());result.put("inputHash",event.task().inputSha256());
        result.put("startedAt",event.task().startedAt()==null?null:event.task().startedAt().toString());
        boolean exited=runtime.hasExited(taskNo,generation) || admission.exitCount(taskNo,generation)>0;
        boolean failed=exited || runtime.viewDevice(event.deviceId())
                .filter(view->view.event().taskNo().equals(taskNo) && view.event().generation()==generation
                        && Set.of("FAILED","RECOVERING").contains(view.status()))
                .filter(view->!runtime.completionPending(taskNo,generation)).isPresent();
        result.put("state",event.kind()==Kind.VERIFIED?"COMPLETED":failed?"FAILED":"IN_PROGRESS");result.put("exitConfirmed",exited);
        if(event.evidence()!=null) {result.put("resultHash",event.evidence().resultSha256());result.put("receiptNo",event.receiptNo());}
        return result;
    }

    private ComputeV2Event retained(String taskNo) {
        return runtime.event(taskNo).orElseGet(()->{
            var payload=admission.retainedPayload(taskNo);
            if(payload==null) throw error(404,"COMPUTE_V2_RECEIPT_NOT_FOUND");
            try {return json.readValue(payload,ComputeV2Event.class);}
            catch(java.io.IOException malformed){throw new IllegalStateException("COMPUTE_V2_EVENT_INVALID",malformed);}
        });
    }

    @Scheduled(fixedDelay=10000,initialDelay=10000,scheduler="computeV2TaskScheduler")
    public void recoverExpiredExecutions() {
        if(!runtime.isReady()) return;
        for(String runner:runnerContainers.keySet()) {
            try {
                var original=runtime.forExecutor(executor(runner));
                if(original.isEmpty()) continue;
                var view=original.get();var event=view.event();
                if(Set.of("STARTING","COMPUTING","VERIFYING").contains(view.status()) && view.leaseExpiresAt()!=null
                        && !view.leaseExpiresAt().isAfter(clock.instant())
                        && !runtime.completionPending(event.taskNo(),event.generation()))
                    runtime.fenceExpired(event.userId(),event.deviceId(),event.generation());
            } catch(RuntimeException unavailable) {
                LOG.warn("Compute v2 lease recovery deferred runner={} reason={}",runner,unavailable.getClass().getSimpleName());
            }
        }
        for(long deviceId:phones.map(PhoneComputeV2Access::devices).orElse(Set.of())) {
            try {
                var current=runtime.viewDevice(deviceId);
                if(current.isEmpty())continue;
                var view=current.get();var event=view.event();
                if(Set.of("STARTING","COMPUTING","VERIFYING").contains(view.status()) && view.leaseExpiresAt()!=null
                        && !view.leaseExpiresAt().isAfter(clock.instant()) && !runtime.completionPending(event.taskNo(),event.generation()))
                    runtime.fenceExpired(event.userId(),deviceId,event.generation());
            } catch(RuntimeException unavailable){LOG.warn("Compute v2 phone lease recovery deferred device={} reason={}",deviceId,unavailable.getClass().getSimpleName());}
        }
    }

    @Scheduled(fixedDelayString="${nexion.compute-v2.dispatch-delay-ms:1000}",scheduler="computeV2TaskScheduler")
    public synchronized void issueEnrolled() {
        if(enrolled.isEmpty() || !runtime.isReady()) return;
        lifecycle.ifPresent(ComputeV2DeviceLifecycleService::finalizePendingEnrolled);
        if(environment.getProperty("nexion.simulated-tasks.enabled",Boolean.class,false))return;
        if(!runtime.canAcceptWork())return;
        List<Long> devices=enrolled.stream().sorted().toList();
        // One shared cursor, bounded work per tick; no account-specific timer, JVM or journal.
        for(int n=0;n<Math.min(12,devices.size());n++) {
            Long priority=n<6?readyDevices.poll():null;
            if(priority!=null)readyDeviceIds.remove(priority);
            long deviceId=priority==null?devices.get(Math.floorMod(scanCursor.getAndIncrement(),devices.size())):priority;
            if(phones.isPresent() && phones.get().manages(deviceId))continue;
            try {
                var current=runtime.viewDevice(deviceId);
                if(current.isPresent()) {
                    var view=current.get();var event=view.event();
                    if("ISSUING".equals(view.status())) {
                        runtime.issue(event.userId(),deviceId,event.instanceNo(),event.task());continue;
                    }
                    if(!Set.of("COMPLETED","FAILED").contains(view.status()) || view.executorId()!=null)continue;
                    runtime.retire(event.userId(),deviceId);
                }
                Issuance issuance=transactions.db(()->freeze(deviceId));
                if(issuance==null) continue;
                issue(issuance);
            } catch(RuntimeException failure) {
                LOG.warn("Compute v2 admission deferred device={} reason={}",deviceId,failure.getClass().getSimpleName());
            }
        }
    }

    private Issuance freeze(long deviceId) {
        return freeze(deviceId,null);
    }
    private void issue(Issuance issuance) {
        String object=issuance.snapshot().inputArtifactRef();
        if(issuance.manifest().length>0)objects.put(object,"application/json",new ByteArrayInputStream(issuance.manifest()),issuance.manifest().length);
        runtime.issue(issuance.identity().ownerId(),issuance.identity().deviceId(),issuance.identity().instanceNo(),issuance.snapshot());
    }
    private Issuance freeze(long deviceId,Identity phone) {
        Identity fleet=new Identity("fleet",0,0,"fleet",environment.getProperty(TestCloudExecutorService.PREFIX+"task-config-id"),
                environment.getProperty(TestCloudExecutorService.PREFIX+"model"),environment.getProperty(TestCloudExecutorService.PREFIX+"model-digest"));
        Identity caller=phone==null?fleet:phone;
        Identity device=phone==null?cloud.computeV2Identity(fleet,deviceId):phone;
        var owned=eligible(caller,deviceId,device.ownerId(),false);
        int routingVram=phone==null?AppTaskAssignmentService.effectiveRoutingVram(owned):0;
        var candidates=new ArrayList<ComputeV2Selection.Candidate>();
        for(var task:assignments.eligibleTasks(routingVram)) {
            if(!allowedTasks.contains(task.taskId()) || task.minVram()==null || task.minVram()>routingVram) continue;
            var saved=policies.find(task.taskId());
            var policy=saved==null?TaskSchedulingSettings.defaults(task.taskClass(),task.modelName()):saved.settings();
            if(phone==null && "EM".equals(task.taskClass()) && "BGE-M3".equals(task.modelName()) && "test-bge-m3-v1".equals(policy.runtimeKey())
                    && policy.runtimeKey().equals(policy.validatorKey())) {
                candidates.add(new ComputeV2Selection.Candidate(task.taskId(),task.name(),task.taskClass(),task.modelName(),
                        task.minReward(),task.maxReward(),saved==null?0:saved.revision(),policy,texts(policy.workloadConfigJson())));
            } else {
                var installed=modalRegistry.installed(task.taskClass(),task.modelName(),policy,phone==null?"CLOUD":"PHONE");
                if(installed.isEmpty())continue;
                JsonNode workload;
                try {workload=modalRegistry.workload(installed.get(),policy.workloadConfigJson());}
                catch(IllegalArgumentException invalid) {LOG.warn("Compute v2 task excluded task={} reason=INVALID_WORKLOAD",task.taskId());continue;}
                candidates.add(new ComputeV2Selection.Candidate(task.taskId(),task.name(),task.taskClass(),task.modelName(),
                        task.minReward(),task.maxReward(),saved==null?0:saved.revision(),policy,List.of(),installed.get(),workload));
            }
        }
        var selected=ComputeV2Selection.draw(candidates,random);var task=selected.candidate();
        var capacity=new HashMap<String,String>();for(var row:assignments.e3CapacityConfig())capacity.put(row.configKey(),row.configValue());
        var projection=E3DeviceCapacityPolicy.project(owned.productCode(),owned.deviceType(),owned.purchasedAt(),owned.activatedAt(),
                LocalDateTime.ofInstant(clock.instant(),DateTimeFormatConfig.BUSINESS_ZONE),capacity);
        long reward=ComputeV2Selection.micros(E3DeviceCapacityPolicy.applyCapacity(BigDecimal.valueOf(selected.rawMicros(),6),projection));
        Object input;
        String modelDigest;
        if(task.installedRuntime()==null) {
            modelDigest=device.modelDigest();
            input=new Manifest(2,task.settings().runtimeKey(),task.settings().validatorKey(),task.taskId(),task.revision(),
                    device.model(),modelDigest,task.settings().executionTimeoutSec(),task.texts());
        } else {
            var installed=task.installedRuntime();modelDigest=installed.modelDigest();
            JsonNode workload=task.workload();
            if(phone!=null) {
                workload=workload.deepCopy();
                for(String field:List.of("inputIds","attentionMask","tokenTypeIds"))for(int row=0;row<2;row++) {
                    var tokens=(ArrayNode)workload.get(field).get(row);
                    for(int n=0;n<8;n++)tokens.insert(n+1,"inputIds".equals(field)?random.nextInt(1000,30522):"attentionMask".equals(field)?1:0);
                }
            }
            input=new ManifestV3(3,task.taskClass(),installed.runtimeKey(),task.settings().validatorKey(),task.taskId(),task.revision(),
                    installed.modelName(),installed.modelRevision(),modelDigest,workload,random.nextInt(Integer.MAX_VALUE),
                    task.settings().executionTimeoutSec());
        }
        byte[] bytes=encode(input);String hash=TestCloudExecutorService.sha256(bytes);var wallet=wallet(device.ownerId());
        Instant now=clock.instant();
        var snapshot=new TaskSnapshot("CV2-NEXT-"+UUID.randomUUID(),task.taskId(),task.name(),task.taskClass(),task.model(),
                "UVEL Compute",task.revision(),selected.expectedSeconds(),task.settings().expectedDurationMinSec(),
                task.settings().expectedDurationMaxSec(),selected.expectedSeconds(),task.settings().executionTimeoutSec(),0,
                now,null,now.plusSeconds(45),now.plusSeconds(task.settings().executionTimeoutSec()+120L),reward,selected.rawMicros(),
                projection.capacityPct().movePointLeft(2),((Number)wallet.get("id")).longValue(),wallet.get("incarnation").toString(),
                new DeviceSnapshot(owned.name(),owned.deviceType(),owned.productCode(),null,null,null,owned.vramTotalGb()),hash,
                modelDigest.substring(7),"compute-v2/TEST/input/"+hash+".json");
        return new Issuance(device,snapshot,bytes);
    }

    private AppTaskAssignmentMapper.DeviceRow eligible(Identity caller,long deviceId,long userId,boolean completing) {
        if(!enrolled.contains(deviceId)) throw error(403,"COMPUTE_V2_DEVICE_NOT_ENROLLED");
        boolean phone=PhoneComputeV2Access.isPhone(caller);
        if(!phone && phones.isPresent() && phones.get().manages(deviceId))throw error(403,"COMPUTE_V2_PHONE_OWNER_REQUIRED");
        Identity identity=phone?caller:cloud.computeV2Identity(caller,deviceId);
        if(identity.ownerId()!=userId || phone && identity.deviceId()!=deviceId) throw error(403,"COMPUTE_V2_OWNER_CHANGED");
        if(phone)phones.orElseThrow().eligible(identity,completing);else cloud.requireComputeV2Paid(identity,completing);
        var device=assignments.lockOwnedDevice(userId,deviceId);
        if(device==null || !identity.instanceNo().equals(device.instanceNo())) throw error(409,"COMPUTE_V2_OWNER_CHANGED");
        Integer pending=admission.pendingDeactivate(deviceId,userId);
        if((!completing && (pending==null || pending!=0)) || device.activatedAt()==null
                || !Set.of("ACTIVE","ONLINE","BUSY","RUNNING").contains(device.status().toUpperCase(Locale.ROOT))
                || Boolean.TRUE.equals(device.dispatchPaused()) || device.pausedReason()!=null&&!device.pausedReason().isBlank())
            throw error(409,"COMPUTE_V2_DEVICE_NOT_ACTIVE");
        if(admission.unresolvedLegacy(userId,deviceId)!=0) throw error(409,"COMPUTE_V2_V1_NOT_DRAINED");
        long ownedEnrolled=assignments.ownedDevices(userId).stream().filter(row->enrolled.contains(row.id()) && row.activatedAt()!=null
                && Set.of("ACTIVE","ONLINE","BUSY","RUNNING","OFFLINE").contains(row.status().toUpperCase(Locale.ROOT))).count();
        if(ownedEnrolled>6) throw error(409,"COMPUTE_V2_ACCOUNT_SLOT_LIMIT");
        return device;
    }

    private Map<String,Object> wallet(long user) {
        var rows=admission.lockWallets(user);
        if(rows.size()!=1) throw error(409,"COMPUTE_V2_WALLET_UNAVAILABLE");return rows.get(0);
    }
    private ComputeV2Runtime.RuntimeView ownedExecution(Identity caller,String runner,String task,long generation,String token) {
        requireExecutor(caller);
        var view=runtime.forExecutor(executionId(caller,runner)).orElseThrow(()->error(409,"COMPUTE_V2_EXECUTOR_NOT_ASSIGNED"));
        requireOwnedTask(caller,view.event());
        if(!view.event().taskNo().equals(task) || view.event().generation()!=generation
                || !constant(token,signature(view.event(),"claim"))) throw error(403,"COMPUTE_V2_PROOF_BINDING_INVALID");
        return view;
    }
    private void validateProof(ComputeV2Runtime.RuntimeView view,Complete proof,boolean received) {
        var event=view.event();Instant now=clock.instant();
        if(!Set.of("COMPUTING","VERIFYING").contains(view.status()) || view.leaseExpiresAt()==null || !view.leaseExpiresAt().isAfter(now)
                || received && (!event.task().proofExpiresAt().isAfter(now) || Math.abs(now.getEpochSecond()-proof.proofTimestamp())>30)
                || !event.task().inputSha256().equals(proof.inputHash()) || !("sha256:"+event.task().modelSha256()).equals(proof.modelDigest())
                || !constant(proof.proofNonce(),signature(event,"nonce"))) throw error(409,"COMPUTE_V2_PROOF_BINDING_INVALID");
    }
    private Map<String,Object> manifest(ComputeV2Runtime.RuntimeView view) {
        var e=view.event();var input=input(e);var result=new LinkedHashMap<String,Object>();
        result.put("state",view.status());result.put("protocolVersion",input.version());result.put("taskNo",e.taskNo());result.put("generation",e.generation());
        result.put("deviceId",e.deviceId());result.put("ownerId",e.userId());result.put("taskType",e.task().taskClass());
        result.put("taskClass",e.task().taskClass());result.put("runtimeKey",input.runtimeKey());result.put("validatorKey",input.validatorKey());
        result.put("startedAt",e.task().startedAt()==null?null:e.task().startedAt().toString());
        if(isPhoneTask(e))result.put("executionKind","PHONE");
        else {
            result.put("executionKind","CLOUD");
            if(view.executorId()!=null && view.executorId().startsWith("compute-v2:"))
                result.put("computeContainerId",computeContainer(input,view.executorId().substring("compute-v2:".length())));
            result.put("validatorContainerId",validationContainer(input));
        }
        result.put("taskConfigId",e.task().taskConfigId());result.put("configVersion",e.task().configVersion());result.put("expectedDurationSec",e.task().expectedSeconds());
        result.put("executionTimeoutSec",e.task().executionTimeoutSec());result.put("model",input.model());result.put("modelDigest",input.modelDigest());
        if(input.version()==2) result.put("texts",input.texts());
        else {result.put("modelName",input.model());result.put("modelRevision",input.modelRevision());result.put("workload",input.workload());result.put("seed",input.seed());}
        result.put("inputHash",e.task().inputSha256());result.put("claimToken",signature(e,"claim"));result.put("proofNonce",signature(e,"nonce"));
        if(e.evidence()!=null){result.put("resultHash",e.evidence().resultSha256());result.put("receiptNo",e.receiptNo());}
        return result;
    }
    private FrozenInput input(ComputeV2Event event) {
        try {
            JsonNode root=json.readerFor(JsonNode.class).with(com.fasterxml.jackson.core.JsonParser.Feature.STRICT_DUPLICATE_DETECTION)
                    .with(com.fasterxml.jackson.databind.DeserializationFeature.FAIL_ON_TRAILING_TOKENS).readValue(inputBytes(event));
            if(root==null || !root.isObject() || !root.path("version").isInt())throw error(409,"COMPUTE_V2_INPUT_MANIFEST_INVALID");
            if(root.get("version").intValue()==2) {
                var input=json.treeToValue(root,Manifest.class);
                if(!"EM".equals(event.task().taskClass()) || !"test-bge-m3-v1".equals(input.runtimeKey())
                        || !input.runtimeKey().equals(input.validatorKey()) || !"bge-m3:567m".equals(input.model())
                        || !Objects.equals(input.taskConfigId(),event.task().taskConfigId()) || input.revision()!=event.task().configVersion()
                        || !Objects.equals(input.modelDigest(),"sha256:"+event.task().modelSha256())
                        || input.executionTimeoutSec()!=event.task().executionTimeoutSec() || input.texts()==null || input.texts().size()!=3
                        || input.texts().stream().anyMatch(text->text==null || text.isBlank() || text.length()>4000))
                    throw error(409,"COMPUTE_V2_INPUT_MANIFEST_INVALID");
                return new FrozenInput(2,input.runtimeKey(),input.validatorKey(),input.model(),"",input.modelDigest(),
                        input.executionTimeoutSec(),input.texts(),null,0);
            }
            Set<String> keys=new HashSet<>();root.fieldNames().forEachRemaining(keys::add);
            if(!keys.equals(Set.of("version","taskClass","runtimeKey","validatorKey","taskConfigId","revision","modelName",
                    "modelRevision","modelDigest","workload","seed","executionTimeoutSec")))throw error(409,"COMPUTE_V2_INPUT_MANIFEST_INVALID");
            var input=json.treeToValue(root,ManifestV3.class);
            if(input.version()!=3 || !Objects.equals(input.taskClass(),event.task().taskClass())
                    || PhoneComputeV2Access.RUNTIME.equals(input.runtimeKey())!=isPhoneTask(event)
                    || !Objects.equals(input.runtimeKey(),input.validatorKey()) || !Objects.equals(input.taskConfigId(),event.task().taskConfigId())
                    || input.revision()!=event.task().configVersion() || !Objects.equals(input.modelName(),event.task().modelName())
                    || !Objects.equals(input.modelDigest(),"sha256:"+event.task().modelSha256())
                    || input.executionTimeoutSec()!=event.task().executionTimeoutSec() || !root.path("seed").isIntegralNumber()
                    || input.seed()<0 || input.seed()>Integer.MAX_VALUE)throw error(409,"COMPUTE_V2_INPUT_MANIFEST_INVALID");
            ComputeV2ModalRegistry.requireKnown(input.taskClass(),input.runtimeKey(),input.modelName(),input.modelRevision());
            ComputeV2ModalRegistry.validateWorkload(input.taskClass(),input.workload());
            return new FrozenInput(3,input.runtimeKey(),input.validatorKey(),input.modelName(),input.modelRevision(),input.modelDigest(),
                    input.executionTimeoutSec(),List.of(),input.workload(),input.seed());
        } catch(java.io.IOException | IllegalArgumentException malformed){throw error(409,"COMPUTE_V2_INPUT_MANIFEST_INVALID");}
    }
    private Map<String,Object> modalTask(ComputeV2Event event,FrozenInput input) {
        var task=new LinkedHashMap<String,Object>();
        task.put("taskNo",event.taskNo());task.put("generation",event.generation());task.put("inputHash",event.task().inputSha256());
        task.put("taskClass",event.task().taskClass());task.put("runtimeKey",input.runtimeKey());task.put("modelRevision",input.modelRevision());
        task.put("workload",input.workload());task.put("seed",input.seed());return task;
    }
    private byte[] inputBytes(ComputeV2Event event) {
        if(!event.task().inputArtifactRef().equals("compute-v2/TEST/input/"+event.task().inputSha256()+".json")) throw error(409,"COMPUTE_V2_INPUT_MANIFEST_INVALID");
        try(var stream=objects.get(event.task().inputArtifactRef())) {byte[] bytes=stream.readNBytes(20001);
            if(bytes.length>20000 || !event.task().inputSha256().equals(TestCloudExecutorService.sha256(bytes))) throw error(409,"COMPUTE_V2_INPUT_MANIFEST_INVALID");
            return bytes;
        } catch(java.io.IOException unavailable){throw error(503,"COMPUTE_V2_INPUT_UNAVAILABLE");}
    }
    private List<String> texts(String config) {
        try {JsonNode root=json.readTree(config);if(root.isEmpty()) return TestCloudExecutorService.TEXTS;
            if(root.size()!=1 || !root.has("texts") || !root.get("texts").isArray() || root.get("texts").size()!=3) throw error(422,"COMPUTE_V2_WORKLOAD_INVALID");
            var values=new ArrayList<String>();for(var text:root.get("texts")) {
                if(!text.isTextual() || text.textValue().isBlank() || text.textValue().length()>4000) throw error(422,"COMPUTE_V2_WORKLOAD_INVALID");
                values.add(text.textValue());}return List.copyOf(values);
        } catch(java.io.IOException malformed){throw error(422,"COMPUTE_V2_WORKLOAD_INVALID");}
    }
    private byte[] encode(Object value) {try{return json.writeValueAsBytes(value);}catch(java.io.IOException invalid){throw new IllegalArgumentException(invalid);}}
    private String signature(ComputeV2Event event,String purpose) {
        return hmac((purpose+":"+event.taskNo()+":"+event.generation()+":"+event.deviceId()+":"+event.task().inputSha256()).getBytes(StandardCharsets.UTF_8));
    }
    private String hmac(byte[] bytes) {
        try {Mac mac=Mac.getInstance("HmacSHA256");mac.init(new SecretKeySpec(proofSecret,"HmacSHA256"));
            return HexFormat.of().formatHex(mac.doFinal(bytes));
        } catch(GeneralSecurityException unavailable){throw new IllegalStateException(unavailable);}
    }
    private boolean constant(String actual,String expected){return actual!=null&&MessageDigest.isEqual(actual.getBytes(StandardCharsets.UTF_8),expected.getBytes(StandardCharsets.UTF_8));}
    private void requireFleet(Identity identity){cloud.requireCurrent(identity);if(!"fleet".equals(identity.executorId()))throw error(403,"TEST_CLOUD_FLEET_REQUIRED");}
    private void requireExecutor(Identity identity){
        if(PhoneComputeV2Access.isPhone(identity)) {
            if(!enrolled.contains(identity.deviceId()) || phones.isEmpty() || !phones.get().manages(identity.deviceId()))
                throw error(403,"COMPUTE_V2_PHONE_NOT_ENROLLED");
        } else requireFleet(identity);
    }
    private void requireOwnedTask(Identity identity,ComputeV2Event event) {
        if(PhoneComputeV2Access.isPhone(identity)) {
            if(!isPhoneTask(event) || identity.ownerId()!=event.userId() || identity.deviceId()!=event.deviceId()
                    || !identity.instanceNo().equals(event.instanceNo()))throw error(403,"COMPUTE_V2_PHONE_OWNER_CHANGED");
        } else if(isPhoneTask(event))throw error(403,"COMPUTE_V2_PHONE_OWNER_REQUIRED");
    }
    private static boolean isPhoneTask(ComputeV2Event event){return Set.of("MOBILE","PHONE").contains(event.task().device().deviceType().toUpperCase(Locale.ROOT));}
    private String executionId(Identity identity,String runner){return PhoneComputeV2Access.isPhone(identity)?identity.executorId():executor(runner);}
    private String computeContainer(FrozenInput input,String runner){
        if(input.version()==2)return sharedBgeContainer.isEmpty()?runnerContainers.get(runner):sharedBgeContainer;
        if(modalContainer.isEmpty())throw error(503,"COMPUTE_V2_MODAL_CONTAINER_NOT_CONFIGURED");return modalContainer;
    }
    private String validationContainer(FrozenInput input){
        if(input.version()==2)return validatorContainer;
        if(modalContainer.isEmpty())throw error(503,"COMPUTE_V2_MODAL_CONTAINER_NOT_CONFIGURED");return modalContainer;
    }
    private String executor(String runner){if(runner==null||!runner.matches("runner-[1-6]"))throw error(422,"COMPUTE_V2_RUNNER_INVALID");return "compute-v2:"+runner;}
    private void enqueue(long deviceId){if(enrolled.contains(deviceId)&&readyDeviceIds.add(deviceId))readyDevices.add(deviceId);}
    private void afterExit(ComputeV2Event event){
        lifecycle.ifPresent(owner->owner.finalizeExited(event.userId(),event.deviceId(),event.taskNo(),event.generation()));
        enqueue(event.deviceId());
    }
    private static Set<Long> ids(String text){var values=new LinkedHashSet<Long>();for(String id:text.split(","))if(!id.isBlank()){
        if(!id.trim().matches("[1-9][0-9]{0,18}"))throw new IllegalArgumentException("COMPUTE_V2_DEVICE_IDS_INVALID");
        long n=Long.parseLong(id.trim());if(n<=0||!values.add(n))throw new IllegalArgumentException("COMPUTE_V2_DEVICE_IDS_INVALID");
    }if(values.size()>6000)throw new IllegalArgumentException("COMPUTE_V2_CAPACITY_EXCEEDED");return Set.copyOf(values);}
    private BizException error(int code,String message){return new BizException(code,message);}
}
