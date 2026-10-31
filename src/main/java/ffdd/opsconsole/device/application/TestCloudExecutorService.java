package ffdd.opsconsole.device.application;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import ffdd.opsconsole.device.dto.AppTaskCompleteRequest;
import ffdd.opsconsole.device.mapper.AppTaskAssignmentMapper;
import ffdd.opsconsole.device.mapper.AppTaskAssignmentMapper.AssignmentRow;
import ffdd.opsconsole.device.mapper.TestCloudExecutorMapper;
import ffdd.opsconsole.device.mapper.TestCloudExecutorMapper.Execution;
import ffdd.opsconsole.device.mapper.TestCloudExecutorMapper.Binding;
import ffdd.opsconsole.shared.api.ApiResult;
import ffdd.opsconsole.shared.audit.AuditLogService;
import ffdd.opsconsole.shared.audit.AuditLogWriteRequest;
import ffdd.opsconsole.shared.exception.BizException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.Principal;
import java.time.Clock;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.*;
import lombok.RequiredArgsConstructor;
import org.springframework.core.env.Environment;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

/** Real BGE inference on declared TEST texts. No commercial workload or production attestation is implied. */
@Service
@RequiredArgsConstructor
public class TestCloudExecutorService {
    public static final String PREFIX = "nexion.compute-task.test-cloud.";
    public static final String KIND = "TEST_BGE_M3_V1";
    public static final String SPEC = "UVEL_TEST_BGE_M3_V1";
    public static final String CLIENT = "UVEL TEST BGE-M3";
    public static final String TASK_NAME = "UVEL TEST BGE-M3 inference";
    public static String publicModel(String model) { return KIND.equals(model)?"BGE-M3":model; }
    public static String publicClient(String model,String client) { return KIND.equals(model)?"UVEL Compute":client; }
    public static String publicTaskName(String model,String name) { return KIND.equals(model)?"BGE-M3 Embedding":name; }
    static final List<String> TEXTS = List.of("UVEL TEST: Cloud Share inference acceptance.",
            "This text is a TEST workload, not a customer order.", "多语言向量嵌入验收：仅用于TEST环境。");
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final HttpClient HTTP = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10))
            .followRedirects(HttpClient.Redirect.NEVER).proxy(new java.net.ProxySelector() {
                public List<java.net.Proxy> select(URI uri) { return List.of(java.net.Proxy.NO_PROXY); }
                public void connectFailed(URI uri, java.net.SocketAddress address, java.io.IOException error) { }
            }).build();
    private final AppTaskAssignmentMapper assignments;
    private final TestCloudExecutorMapper mapper;
    private final AppTaskAssignmentService settlement;
    private final AuditLogService audit;
    private final Clock clock;
    private final Environment environment;
    private final TestCloudRequests requests;

    public record Identity(String executorId,long ownerId,long deviceId,String instanceNo,String taskConfigId,
                           String model,String modelDigest) implements Principal {
        public String getName() { return "test-cloud:" + executorId; }
        public String toString() { return getName(); }
    }
    public record CompleteRequest(String specVersion,String inputHash,String modelDigest,String resultArtifactBase64,
                                  String resultHash,String proofNonce,Long proofTimestamp,String claimToken) { }
    public record RollbackReviewRequest(String originalRequestHash,String resultHash) { }
    private record RollbackScope(long deviceId,String instanceNo,String originalRequestHash) { }
    private static final Map<String,RollbackScope> REVIEWED_ROLLBACK_REQUESTS=Map.of(
            "CTA-FDDE9396AE554345A116E4026D5590A4",new RollbackScope(1204,"DEV-TI-27D1233EBF21434FB85EC6B49D66507F",
                    "3bd943a7efe51ac4e190fe6cc37c075627f615e6479d79097fc57f51c342e54d"),
            "CTA-DECD77CE4A6A47029334B35142A3B23D",new RollbackScope(1206,"NEX-ORD-89A41D4C6261D157C6D20830",
                    "fdbb7d70769efda6050bc09e0a45dfb1d86b4da3376010bf532b2b688725b114"),
            "CTA-E9CA6111BC2841C3A87A7E1B4ED478DE",new RollbackScope(1209,"NEX-ORD-5A61BFA35341348072293238",
                    "ad8bad588e5adfc2252c554fef896e709349684114c59b5559f830a43fdf4267"),
            "CTA-56A3A0D522F6404C9E4AE65A6478A052",new RollbackScope(1212,"DEV-TI-ADFCB790173045B89BC7E90F1058F275",
                    "ac1a286bb35368bd50254db9b0d4ae6aee478654696f4bce73de635411f62d2a"));
    private record StabilityScope(long deviceId,String instanceNo,String originalHash,String nextKeyHash,String completeKeyHash) { }
    private static final Map<String,StabilityScope> STABILITY_SCOPES=Map.of(
            "CTA-F1D89023D75F473E8D7A57F4B0E6662D",new StabilityScope(1204,"DEV-TI-27D1233EBF21434FB85EC6B49D66507F",
                    "4f9d90a0bf0f66a7fb6b05bce77e2670e53551a49b1d2c446a21665eaa0d239f",
                    "ffc90539ef743e37a71e24bf092e44736cae4a71879e75303759d17f61281c80","71f71ce5fd725a22a4de25ffb3a0b5df2933affe5392381efb4f776ff224d0a2"),
            "CTA-863FE539FA4943CB8695283A2BDCDB5F",new StabilityScope(1209,"NEX-ORD-5A61BFA35341348072293238",
                    "e86c62a5dedae748aada2e98285f90e10f2c5b89e8eb46542760f9033928c962",
                    "126e0723f6e5d1bcb1cb9842e48f1dfb2da41ec9868e6c80834077c68960d2f5","be57c4550e1bc4c60e2a7841e178d47db7ca507585631482ed98a07ddaef17af"),
            "CTA-D8C51049769A469FB39DB21FEFA5374C",new StabilityScope(1214,"DEV-CPR-A70B39752516410385509B908766F165",
                    "c5701921271e89cff7c3411f60cda32ef283670b2459b65953a655a97f5e19e4",
                    "7cb68b22fee7d81c27f8150559752474ef4bf4762d0d84fb7c689afb447cf549","c955a547a9df2bce8c79da703a04287a99c686f26922d3464365ef851ff3cbab"));
    private static final String ROLLBACK_INPUT="400cf267a947b6e8bf374d1ea61c6013391618505a7b934a16b099853a8fcc6a";
    private static final String ROLLBACK_RESULT="3d47ee75a2c38bd87d4a8d340d60298eb7decf8d15f09e15aef86b62c46a6187";
    /** Only constructed after locked manifest, live binding and independent inference validation. */
    public record CloudProof(long ownerId,long deviceId,String instanceNo,String taskNo,String nonce,
                             String proofHash,String executorId,String inputHash,String resultHash,String modelDigest) { }

    public void requireEnabled() {
        if (!"true".equals(property("enabled")) || !"TEST".equals(property("deployment-scope"))
                || !Set.of(environment.getActiveProfiles()).equals(Set.of("dev"))
                || !"none".equals(environment.getProperty("server.forward-headers-strategy"))) {
            throw error(503,"TEST_CLOUD_DISABLED");
        }
    }

    public Identity authenticate(String authorization) {
        return authenticate(authorization,null);
    }

    public Identity authenticate(String authorization,String executorId) {
        requireEnabled();
        try {
            if (authorization == null || !authorization.matches("Bearer ct1_[A-Za-z0-9_-]{43}")) throw new IllegalArgumentException();
            String encoded = authorization.substring(11);
            byte[] bytes = Base64.getUrlDecoder().decode(encoded);
            String expected = property("credential-sha256");
            if (bytes.length != 32 || !Base64.getUrlEncoder().withoutPadding().encodeToString(bytes).equals(encoded)
                    || !expected.matches("[a-f0-9]{64}")
                    || !MessageDigest.isEqual(digest(bytes),HexFormat.of().parseHex(expected))) throw new IllegalArgumentException();
            return requests.outside(()->requests.db(()->authenticatedIdentity(executorId)));
        } catch (IllegalArgumentException invalid) { throw error(401,"TEST_CLOUD_AUTH_INVALID"); }
    }

    private Identity authenticatedIdentity(String executorId) {
            Identity identity;
            if(executorId==null || executorId.isBlank() || configured().executorId().equals(executorId)) identity=configured();
            else {
                if(!fleetEnabled()) throw new IllegalArgumentException();
                if("fleet".equals(executorId)) identity=fleetIdentity();
                else {
                    safe(executorId,32);
                    Binding registered=mapper.binding(executorId);
                    if(registered!=null) identity=identity(registered);
                    else {
                        if(!executorId.matches("fleet-[1-9][0-9]{0,18}")) throw new IllegalArgumentException();
                        long deviceId=Long.parseLong(executorId.substring(6));
                        var candidate=mapper.fleetCandidate(fleetCutoff(),tradeinFleetCutoff(environment),deviceId,configured().deviceId(),legacyEnabled());
                        if(candidate==null) throw new IllegalArgumentException();
                        identity=new Identity(executorId,candidate.ownerId(),deviceId,candidate.instanceNo(),
                                configured().taskConfigId(),configured().model(),configured().modelDigest());
                    }
                }
            }
            requireCurrent(identity); return identity;
    }

    private Identity configured() {
        long owner = Long.parseLong(property("owner-id")), device = Long.parseLong(property("device-id"));
        String executor = safe(property("executor-id"),32), instance = safe(property("instance-no"),128);
        String config = safe(property("task-config-id"),64), model = property("model");
        String modelDigest = property("model-digest");
        if (owner <= 0 || device <= 0 || executor.length()<3 || !model.matches("bge-m3(?::[A-Za-z0-9._-]+)?")
                || !"bge-m3:567m".equals(model)
                || !"sha256:7907646426070047a77226ac3e684fbbe8410524f7b4a74d02837e43f2146bab".equals(modelDigest)) throw new IllegalArgumentException();
        return new Identity(executor,owner,device,instance,config,model,modelDigest);
    }

    public void requireCurrent(Identity identity) {
        if(org.springframework.transaction.support.TransactionSynchronizationManager.isActualTransactionActive()) {
            requireCurrentLocked(identity); return;
        }
        requests.outside(()->requests.db(()->{ requireCurrentLocked(identity); return null; }));
    }
    private void requireCurrentLocked(Identity identity) {
        requireEnabled();
        try {
            if(!property("credential-sha256").matches("[a-f0-9]{64}")) throw new IllegalArgumentException();
            if(configured().equals(identity)) return;
            if(!fleetEnabled()) throw new IllegalArgumentException();
            if(fleetIdentity().equals(identity)) return;
            Binding binding=mapper.binding(identity.executorId());
            if(binding!=null) {
                if(!identity(binding).equals(identity)) throw new IllegalArgumentException();
            } else {
                if(!identity.executorId().equals("fleet-"+identity.deviceId())) throw new IllegalArgumentException();
                var candidate=mapper.fleetCandidate(fleetCutoff(),tradeinFleetCutoff(environment),identity.deviceId(),configured().deviceId(),legacyEnabled());
                if(candidate==null || candidate.ownerId()!=identity.ownerId() || !candidate.instanceNo().equals(identity.instanceNo())
                        || !configured().taskConfigId().equals(identity.taskConfigId())
                        || !configured().model().equals(identity.model()) || !configured().modelDigest().equals(identity.modelDigest())) throw new IllegalArgumentException();
            }
        } catch (IllegalArgumentException invalid) { throw error(401,"TEST_CLOUD_AUTH_INVALID"); }
    }

    private boolean fleetEnabled() { return "true".equals(property("fleet-enabled")); }
    private boolean legacyEnabled() { return "true".equals(property("fleet-legacy-enabled")); }
    private LocalDateTime fleetCutoff() {
        try { return LocalDateTime.parse(property("fleet-started-at")); }
        catch(RuntimeException invalid) { throw new IllegalArgumentException("TEST_CLOUD_FLEET_CUTOFF_INVALID"); }
    }
    static LocalDateTime tradeinFleetCutoff(Environment environment) {
        if (!"true".equals(environment.getProperty(PREFIX+"fleet-tradein-enabled"))) return null;
        try { return LocalDateTime.parse(environment.getProperty(PREFIX+"fleet-tradein-started-at","")); }
        catch(RuntimeException invalid) { throw new IllegalArgumentException("TEST_CLOUD_TRADEIN_CUTOFF_INVALID"); }
    }
    private Identity fleetIdentity() { return new Identity("fleet",0,0,"fleet",configured().taskConfigId(),configured().model(),configured().modelDigest()); }
    private Identity identity(Binding b) { return new Identity(b.executorId(),b.ownerId(),b.deviceId(),b.instanceNo(),b.taskConfigId(),b.model(),b.modelDigest()); }
    private Binding binding(Identity i) { return new Binding(i.executorId(),i.ownerId(),i.deviceId(),i.instanceNo(),i.taskConfigId(),i.model(),i.modelDigest()); }
    private boolean fleet(Identity i) { return i.executorId().startsWith("fleet-"); }

    /** Observation only: a device executor waits without renewing runtime or claiming work. */
    public ApiResult<Map<String,Object>> eligibility(Identity i) {
        return requests.outside(()->requests.db(()->eligibilityLocked(i)));
    }
    private ApiResult<Map<String,Object>> eligibilityLocked(Identity i) {
        requireCurrent(i);
        if(!configured().equals(i) && !fleet(i)) throw error(403,"TEST_CLOUD_DEVICE_EXECUTOR_REQUIRED");
        lockBinding(i,false);
        if(fleet(i)) {
            Binding registered=mapper.lockBinding(i.executorId());
            if(registered!=null && !binding(i).equals(registered)) throw error(401,"TEST_CLOUD_AUTH_INVALID");
        }
        var device=assignments.lockOwnedDevice(i.ownerId(),i.deviceId());
        var active=mapper.lockActiveTaskNos(i.ownerId(),i.deviceId());
        if(active.size()>1) throw error(409,"TEST_CLOUD_ACTIVE_TASK_CONFLICT");
        boolean eligible=device.activatedAt()!=null
                && Set.of("ACTIVE","ONLINE","BUSY","RUNNING").contains(Objects.toString(device.status(),"").toUpperCase(Locale.ROOT))
                && !Boolean.TRUE.equals(device.dispatchPaused())
                && (device.pausedReason()==null || device.pausedReason().isBlank());
        if(fleet(i)) {
            var admission=!legacyEnabled()?null:mapper.legacyAdmission(i.ownerId(),i.deviceId(),i.instanceNo(),null,i.taskConfigId());
            eligible=eligible && Integer.valueOf(1).equals(mapper.liveFleetBinding(fleetCutoff(),tradeinFleetCutoff(environment),
                    i.ownerId(),i.deviceId(),i.instanceNo(),false,admission!=null));
        } else {
            eligible=eligible && mapper.eligibleBinding(i.ownerId(),i.deviceId(),i.instanceNo())==1
                    && assignments.lockPaidCloudShareDailyNex(i.ownerId(),i.deviceId())!=null;
        }
        Map<String,Object> result=bindings(i);
        result.put("eligible",eligible); result.put("activeTaskNo",active.isEmpty()?null:active.get(0));
        return ApiResult.ok(result);
    }

    public ApiResult<Map<String,Object>> instances(Identity i,long afterDeviceId,int requestedLimit) {
        return requests.outside(()->requests.db(()->instancesLocked(i,afterDeviceId,requestedLimit)));
    }
    private ApiResult<Map<String,Object>> instancesLocked(Identity i,long afterDeviceId,int requestedLimit) {
        requireCurrent(i);
        if(!fleetIdentity().equals(i) || !fleetEnabled() || afterDeviceId<0) throw error(403,"TEST_CLOUD_FLEET_REQUIRED");
        int limit=Math.max(1,Math.min(100,requestedLimit));
        var candidates=mapper.fleetCandidates(fleetCutoff(),tradeinFleetCutoff(environment),afterDeviceId,configured().deviceId(),limit+1,legacyEnabled());
        List<Map<String,Object>> items=new ArrayList<>();
        for(var c:candidates.stream().limit(limit).toList()) {
            Binding registered=mapper.binding("fleet-"+c.deviceId());
            Identity target=registered==null?new Identity("fleet-"+c.deviceId(),c.ownerId(),c.deviceId(),c.instanceNo(),
                    configured().taskConfigId(),configured().model(),configured().modelDigest()):identity(registered);
            // A changed owner or instance must never silently inherit an old executor identity.
            if(target.ownerId()!=c.ownerId() || !target.instanceNo().equals(c.instanceNo())) continue;
            items.add(bindings(target));
        }
        Map<String,Object> result=bindings(i); result.put("instances",items); result.put("hasMore",candidates.size()>limit);
        result.put("nextAfterDeviceId",candidates.isEmpty()?afterDeviceId:candidates.get(Math.min(limit,candidates.size())-1).deviceId());
        return ApiResult.ok(result);
    }

    public ApiResult<Map<String,Object>> next(Identity identity,String key) {
        return next(identity,key,sha256("{}"),null,null);
    }
    public ApiResult<Map<String,Object>> next(Identity identity,String key,String bodyHash,String recoveryToken,Long generation) {
        requireEnabled(); key(key);
        requests.outside(()->requests.db(()->{
            requireCurrent(identity);
            Execution byKey=mapper.readByNextKey(identity.executorId(),sha256(key));
            Execution active=mapper.readActiveExecution(identity.executorId(),identity.ownerId(),identity.deviceId());
            if(byKey!=null && !requests.durableNext(identity,byKey.taskNo()) || active!=null && !requests.durableNext(identity,active.taskNo()))
                throw error(409,"TEST_CLOUD_LEGACY_NEXT_RECONCILE_ONLY");
            return null;
        }));
        return requests.execute(identity,"NEXT",key,bodyHash,null,null,recoveryToken,generation,
                ticket->requests.commit(ticket,()->nextLocked(identity,key)));
    }
    private ApiResult<Map<String,Object>> nextLocked(Identity identity,String key) {
        requireCurrent(identity); key(key); lockBinding(identity);
        if(fleet(identity)) {
            mapper.register(binding(identity),now());
            if(!binding(identity).equals(mapper.binding(identity.executorId()))) throw error(409,"TEST_CLOUD_BINDING_CONFLICT");
        }
        Execution replay = mapper.byNextKey(identity.executorId(),sha256(key));
        if (replay != null) {
            requireManifest(identity,replay);
            if(!requests.durableNext(identity,replay.taskNo())) throw error(409,"TEST_CLOUD_LEGACY_NEXT_RECONCILE_ONLY");
            return ApiResult.ok(claimView(identity,replay,task(identity,replay.taskNo())));
        }
        AssignmentRow active = assignments.lockActiveAssignment(identity.ownerId(),identity.deviceId(),"PRODUCTION");
        if (active != null) {
            Execution existing = mapper.lock(active.taskNo());
            if (existing != null) {
                if(!requests.durableNext(identity,existing.taskNo())) throw error(409,"TEST_CLOUD_LEGACY_NEXT_RECONCILE_ONLY");
                requireManifest(identity,existing); requireLiveTask(identity,existing,active);
                return ApiResult.ok(claimView(identity,existing,active));
            }
            var admission=fleet(identity) && legacyEnabled()
                    ? mapper.legacyAdmission(identity.ownerId(),identity.deviceId(),identity.instanceNo(),active.taskNo(),identity.taskConfigId()) : null;
            boolean authorized=fleet(identity) ? admission!=null : active.taskNo().equals(property("cancel-unexecuted-task-no"));
            if (!authorized || !identity.taskConfigId().equals(active.taskId()))
                throw error(409,"TEST_CLOUD_UNMANIFESTED_TASK_BLOCKED");
            if (mapper.countActive(identity.ownerId(),identity.deviceId())!=1)
                throw error(409,"TEST_CLOUD_ACTIVE_TASK_CONFLICT");
            if (mapper.cancelUnexecuted(identity.ownerId(),identity.deviceId(),active.taskNo(),identity.taskConfigId(),now())!=1)
                throw error(409,"TEST_CLOUD_UNMANIFESTED_TASK_BLOCKED");
            assignments.clearRuntimeTask(identity.ownerId(),identity.deviceId(),active.taskNo(),now());
            Map<String,Object> detail=new LinkedHashMap<>();
            detail.put("reason","No input or execution evidence; replaced by explicit TEST inference");
            if(admission!=null) { detail.put("oldTaskNo",admission.oldTaskNo()); detail.put("reason",admission.reason()); detail.put("evidenceSha256",admission.evidenceSha256()); }
            record(identity,"TEST_CLOUD_UNEXECUTED_TASK_CANCELLED",active.taskNo(),detail);
        }
        var taskLock = assignments.lockDeviceTaskLock(identity.ownerId(),identity.deviceId(),"PRODUCTION");
        if (taskLock != null && taskLock.lockUntil()!=null && taskLock.lockUntil().isAfter(now())) {
            Map<String,Object> idle = bindings(identity); idle.put("idle",true); idle.put("retryAfterSeconds",60); return ApiResult.ok(idle);
        }
        heartbeatLocked(identity,null);
        var claimed = settlement.claimForTestCloud(identity.ownerId(),identity.deviceId()).getData();
        if (claimed == null || !identity.taskConfigId().equals(claimed.taskId()) || !"BGE-M3".equals(claimed.model())) {
            throw error(409,"TEST_CLOUD_TASK_CONFIG_INVALID");
        }
        AssignmentRow created = task(identity,claimed.taskNo());
        Execution e = new Execution(created.taskNo(),identity.executorId(),identity.ownerId(),identity.deviceId(),identity.instanceNo(),
                identity.taskConfigId(),sha256(key),random(),json(TEXTS),sha256(json(TEXTS)),identity.model(),identity.modelDigest(),
                created.completionNonce(),null,null,null,null,now());
        if (mapper.insert(e)!=1 || mapper.acknowledge(identity.ownerId(),identity.deviceId(),created.taskNo(),identity.taskConfigId(),now())!=1) {
            throw error(409,"TEST_CLOUD_CLAIM_CONFLICT");
        }
        heartbeatLocked(identity,created.taskNo());
        record(identity,"TEST_CLOUD_TASK_CLAIMED",created.taskNo(),Map.of("inputHash",e.inputHash(),"modelDigest",e.modelDigest(),
                "executionKind",KIND,"taskConfigId",identity.taskConfigId()));
        requireCurrent(identity);
        Map<String,Object> result=claimView(identity,e,task(identity,e.taskNo())); result.put("requestCreatedTask",true);
        return ApiResult.ok(result);
    }

    public ApiResult<Map<String,Object>> manifest(Identity identity,String taskNo,String nextKey) {
        return requests.outside(()->requests.db(()->manifestLocked(identity,taskNo,nextKey)));
    }
    private ApiResult<Map<String,Object>> manifestLocked(Identity identity,String taskNo,String nextKey) {
        requireCurrent(identity); key(nextKey); lockBinding(identity,false);
        AssignmentRow task=task(identity,safe(taskNo,96));
        Execution e=mapper.lock(taskNo); requireManifest(identity,e);
        if(!sha256(nextKey).equals(e.nextKeyHash())) throw error(403,"TEST_CLOUD_NEXT_KEY_INVALID");
        AssignmentRow active=assignments.lockActiveAssignment(identity.ownerId(),identity.deviceId(),"PRODUCTION");
        if(active==null || !taskNo.equals(active.taskNo()) || mapper.countActive(identity.ownerId(),identity.deviceId())!=1)
            throw error(409,"TEST_CLOUD_ACTIVE_TASK_CONFLICT");
        requireLiveTask(identity,e,task);
        lockBinding(identity,true,true);
        if(e.resultHash()!=null || e.resultArtifact()!=null || e.completeKeyHash()!=null || e.completeBodyHash()!=null
                || mapper.settlementEvidence(taskNo)!=0) throw error(409,"TEST_CLOUD_RESUME_REQUIRES_RECONCILIATION");
        // This GET only reads the original locked task. It never claims, cancels, heartbeats or renews a lease.
        Map<String,Object> result=claimView(identity,e,task);
        result.put("resumeAllowed",true); result.put("nextKeyHash",e.nextKeyHash());
        return ApiResult.ok(result);
    }

    public ApiResult<Map<String,Object>> heartbeat(Identity identity,String taskNo,String claimToken) {
        return requests.outside(()->requests.db(()->heartbeatLockedRequest(identity,taskNo,claimToken)));
    }
    private ApiResult<Map<String,Object>> heartbeatLockedRequest(Identity identity,String taskNo,String claimToken) {
        requireCurrent(identity); lockBinding(identity,false);
        AssignmentRow active=assignments.lockActiveAssignment(identity.ownerId(),identity.deviceId(),"PRODUCTION");
        lockBinding(identity,true,active!=null);
        if (taskNo!=null) {
            Execution e=mapper.lock(safe(taskNo,96)); requireManifest(identity,e);
            if (!Objects.equals(e.claimToken(),claimToken)) throw error(403,"TEST_CLOUD_CLAIM_INVALID");
            requireLiveTask(identity,e,task(identity,taskNo));
            if (active==null || !taskNo.equals(active.taskNo())) throw error(409,"TEST_CLOUD_TASK_INVALID");
        } else if (active!=null) {
            Execution e=mapper.lock(active.taskNo());
            if(e==null) throw error(409,"TEST_CLOUD_UNMANIFESTED_TASK_BLOCKED");
            requireManifest(identity,e); requireLiveTask(identity,e,active);
        }
        String current=active==null?null:active.taskNo(); heartbeatLocked(identity,current);
        Map<String,Object> result=bindings(identity); result.put("heartbeatAt",clock.millis());
        if(taskNo!=null) result.put("taskNo",taskNo);
        result.put("activeTaskNo",current); return ApiResult.ok(result);
    }

    public ApiResult<Map<String,Object>> complete(Identity identity,String taskNo,String key,CompleteRequest request) {
        return complete(identity,taskNo,key,request,sha256(json(request)),null,null);
    }
    public ApiResult<Map<String,Object>> complete(Identity identity,String taskNo,String key,CompleteRequest request,
            String bodyHash,String recoveryToken,Long generation) {
        return complete(identity,taskNo,key,request,bodyHash,recoveryToken,generation,null);
    }
    public ApiResult<Map<String,Object>> complete(Identity identity,String taskNo,String key,CompleteRequest request,
            String bodyHash,String recoveryToken,Long generation,Long stabilityReviewId) {
        requireEnabled(); key(key); safe(taskNo,96);
        if(Set.of(1204L,1209L,1214L,1152L,1380L).contains(identity.deviceId())) {
            requests.outside(()->requests.db(()->{
                requireCurrent(identity);
                if(!requests.durableNext(identity,taskNo) && requests.read(identity,"COMPLETE",key,bodyHash,taskNo)==null) {
                    if(stabilityReviewId==null || request==null) throw error(409,"TEST_CLOUD_STABILITY_REVIEW_REQUIRED");
                    var review=requireStabilityReview(identity,taskNo,key,null,null,stabilityReviewId,request.resultHash(),false);
                    JsonNode detail=readReview(review.detailJson());
                    requests.consumeStabilityReview(identity,taskNo,key,bodyHash,review,detail.path("originalRequestHash").asText(),
                            detail.path("terminationApproved").isBoolean() && detail.path("terminationApproved").booleanValue());
                }
                return null;
            }));
        }
        return requests.execute(identity,"COMPLETE",key,bodyHash,taskNo,request==null?null:request.proofTimestamp(),recoveryToken,generation,ticket->{
            boolean replay=requests.db(()->completionSnapshot(identity,taskNo,key,bodyHash,request));
            requests.authorizeProof(ticket);
            byte[] artifact;
            if(replay) artifact=null;
            else {
                if(org.springframework.transaction.support.TransactionSynchronizationManager.isActualTransactionActive())
                    throw new IllegalStateException("TEST_CLOUD_MODEL_TRANSACTION_FORBIDDEN");
                double[][] independentlyComputed=infer(identity);
                artifact=TestCloudBgeArtifact.verify(request.resultArtifactBase64(),request.resultHash(),independentlyComputed);
            }
            return requests.commit(ticket,()->completeLocked(identity,taskNo,key,bodyHash,request,artifact,stabilityReviewId));
        });
    }
    private boolean completionSnapshot(Identity identity,String taskNo,String key,String bodyHash,CompleteRequest request) {
        requireCurrent(identity); key(key); safe(taskNo,96);
        requireBinding(identity,false,false,false);
        AssignmentRow task=requireTask(identity,assignments.assignment(identity.ownerId(),taskNo,"PRODUCTION"));
        Execution e=mapper.execution(taskNo); requireManifest(identity,e);

        if(!completionReplay(e,key,bodyHash)) {
            requireBinding(identity,true,true,false);
            requireLiveTask(identity,e,task);
            requireSameActivation(identity,task,false);
            requireCompletionProof(e,request);
            requireCompletionRuntime(identity,taskNo,assignments.testWorkerRuntime(identity.ownerId(),identity.deviceId(),identity.instanceNo()));
            TestCloudBgeArtifact.decode(request.resultArtifactBase64(),request.resultHash());
            return false;
        }
        return true;
    }
    private ApiResult<Map<String,Object>> completeLocked(Identity identity,String taskNo,String key,String bodyHash,
            CompleteRequest request,byte[] artifact,Long stabilityReviewId) {
        // Current locking reads revalidate every mutable authority after inference, in the original lock order.
        lockBinding(identity,false);
        AssignmentRow task=task(identity,taskNo); Execution e=mapper.lock(taskNo); requireManifest(identity,e);
        if(fleet(identity)) {
            Binding current=mapper.lockBinding(identity.executorId());
            if(!binding(identity).equals(current)) throw error(401,"TEST_CLOUD_AUTH_INVALID");
        }
        if(completionReplay(e,key,bodyHash)) return ApiResult.ok(receiptView(identity,e,task));
        if(artifact==null) throw error(409,"TEST_CLOUD_COMPLETION_CONFLICT");
        lockBinding(identity,true,true);
        requireLiveTask(identity,e,task);
        requireSameActivation(identity,task,true);
        requireCompletionProof(e,request);
        requireCompletionRuntime(identity,taskNo,assignments.lockTestWorkerRuntime(identity.ownerId(),identity.deviceId(),identity.instanceNo()));
        if(stabilityReviewId!=null) requireStabilityReview(identity,taskNo,key,null,null,stabilityReviewId,request.resultHash(),false);
        String proofHash=domainProof(identity,e,request.resultHash());
        CloudProof proof=new CloudProof(identity.ownerId(),identity.deviceId(),identity.instanceNo(),taskNo,e.proofNonce(),
                proofHash,identity.executorId(),e.inputHash(),request.resultHash(),e.modelDigest());
        if(mapper.saveResult(taskNo,request.resultHash(),artifact,sha256(key),bodyHash)!=1) throw error(409,"TEST_CLOUD_COMPLETION_CONFLICT");
        var normalRequest=new AppTaskCompleteRequest(request.resultHash(),KIND,identity.executorId(),request.proofNonce(),request.proofTimestamp(),null);
        settlement.completeForTestCloud(taskNo,normalRequest,proof);
        requireCurrent(identity);
        record(identity,"TEST_CLOUD_RESULT_VERIFIED",taskNo,Map.of("inputHash",e.inputHash(),"resultHash",request.resultHash(),
                "model",e.modelTag(),"modelDigest",e.modelDigest(),"proofHash",proofHash,"artifactBytes",artifact.length));
        return ApiResult.ok(receiptView(identity,mapper.lock(taskNo),task(identity,taskNo)));
    }

    private boolean completionReplay(Execution e,String key,String bodyHash) {
        if(e.resultHash()==null) return false;
        if(!Objects.equals(e.completeKeyHash(),sha256(key)) || !Objects.equals(e.completeBodyHash(),bodyHash))
            throw error(409,"TEST_CLOUD_COMPLETION_REPLAYED");
        return true;
    }

    public ApiResult<Map<String,Object>> requestStatus(Identity i,String operation,String key,String hash,String taskNo) {
        return requests.outside(()->requests.db(()->{
            requireCurrent(i);
            if("COMPLETE".equals(operation)) safe(taskNo,96);
            var value=requests.read(i,operation,key,hash,taskNo);
            Map<String,Object> result=bindings(i); result.putAll(requests.view(value,operation,key,hash));
            String boundTask=value==null?null:value.taskNo();
            // Legacy NEXT is reconciled only from a positive, unique original key binding. No durable
            // record is fabricated and a missing manifest is never authority to claim another task.
            if(value==null && "NEXT".equals(operation) && sha256("{}").equals(hash)) {
                Execution e=mapper.readByNextKey(i.executorId(),sha256(key));
                if(e!=null) {
                    requireManifest(i,e);
                    AssignmentRow original=requireTask(i,assignments.assignment(i.ownerId(),e.taskNo(),"PRODUCTION"));
                    result.put("state","COMMITTED"); result.put("taskNo",e.taskNo());
                    result.put("response",claimView(i,e,original)); result.put("legacyPositiveBinding",true); boundTask=e.taskNo();
                }
            }
            AssignmentRow task=boundTask==null?null:requireTask(i,assignments.assignment(i.ownerId(),boundTask,"PRODUCTION"));
            result.put("taskStatus",task==null?null:task.status());
            result.put("leaseExpiresAt",task==null || task.leaseExpiresAt()==null?null:epoch(task.leaseExpiresAt()));
            result.put("proofExpiresAt",task==null || task.proofExpiresAt()==null?null:epoch(task.proofExpiresAt()));
            result.put("proofTimestamp",value==null?null:value.proofTimestamp());
            result.put("terminationAllowed",value!=null && "COMPLETE".equals(operation) && "ROLLED_BACK".equals(value.state())
                    && value.responseJson()==null && value.recoveryToken()!=null && task!=null
                    && (value.proofValidated() && (value.proofTimestamp()!=null && value.proofTimestamp()<clock.millis()-120_000
                    || task.leaseExpiresAt()!=null && !task.leaseExpiresAt().isAfter(now())
                    || task.proofExpiresAt()!=null && !task.proofExpiresAt().isAfter(now()))
                    || value.reviewedTermination() && task.leaseExpiresAt()!=null && !task.leaseExpiresAt().isAfter(now())));
            if(Boolean.TRUE.equals(result.get("terminationAllowed"))) {
                Execution e=mapper.execution(boundTask); requireManifest(i,e);
                result.put("terminationAllowed",Set.of("CLAIMED","RUNNING").contains(task.status())
                        && e.resultHash()==null && e.resultArtifact()==null && e.completeKeyHash()==null && e.completeBodyHash()==null
                        && mapper.settlementEvidence(boundTask)==0);
            }
            return ApiResult.ok(result);
        }));
    }

    public ApiResult<Map<String,Object>> stabilityReview(Identity i,String taskNo,String completeKey,String nextKey,RollbackReviewRequest request) {
        return requests.outside(()->requests.db(()->{
            requireCurrent(i); key(completeKey); key(nextKey); safe(taskNo,96);
            var review=requireStabilityReview(i,taskNo,completeKey,nextKey,request.originalRequestHash(),null,request.resultHash(),true);
            JsonNode detail=readReview(review.detailJson());
            Execution e=mapper.lock(taskNo); requireManifest(i,e);
            Map<String,Object> result=bindings(i);
            result.putAll(Map.of("taskNo",taskNo,"inputHash",e.inputHash(),"resultHash",request.resultHash(),
                    "originalRequestHash",request.originalRequestHash(),"completeKeyHash",sha256(completeKey),"nextKeyHash",e.nextKeyHash(),
                    "reviewId",review.id(),"evidenceSha256",detail.path("evidenceSha256").asText(),"maxAttempts",1,"rollbackConfirmed",true));
            result.put("recoveryAllowed",!requests.stabilityUsed(taskNo));
            return ApiResult.ok(result);
        }));
    }
    private ffdd.opsconsole.device.mapper.TestCloudRequestMapper.LegacyReview requireStabilityReview(Identity i,String taskNo,
            String completeKey,String nextKey,String originalHash,Long reviewId,String resultHash,boolean requireUnused) {
        StabilityScope scope=STABILITY_SCOPES.get(taskNo);
        if(scope==null || scope.deviceId()!=i.deviceId() || !scope.instanceNo().equals(i.instanceNo()) || !fleet(i)
                || !scope.completeKeyHash().equals(sha256(completeKey))) throw error(403,"TEST_CLOUD_STABILITY_SCOPE_INVALID");
        lockBinding(i,false); AssignmentRow task=task(i,taskNo); Execution e=mapper.lock(taskNo); requireManifest(i,e);
        if(!binding(i).equals(mapper.lockBinding(i.executorId()))) throw error(401,"TEST_CLOUD_AUTH_INVALID");
        if(e.resultHash()!=null || e.resultArtifact()!=null || e.completeKeyHash()!=null || e.completeBodyHash()!=null
                || !Integer.valueOf(1).equals(mapper.rollbackUnconsumedTask(taskNo,i.ownerId(),i.deviceId()))
                || !mapper.rollbackReceiptEvidence(taskNo).isEmpty() || !mapper.rollbackLedgerEvidence(taskNo).isEmpty()
                || !mapper.rollbackEventEvidence(taskNo).isEmpty()) throw error(409,"TEST_CLOUD_STABILITY_REQUIRES_RECONCILIATION");
        if(!ROLLBACK_INPUT.equals(e.inputHash()) || !ROLLBACK_RESULT.equals(resultHash) || !scope.nextKeyHash().equals(e.nextKeyHash()))
            throw error(403,"TEST_CLOUD_STABILITY_SCOPE_INVALID");
        var reviews=requests.stabilityReviews(taskNo,i.ownerId());
        if(reviews.size()!=1) throw error(409,"TEST_CLOUD_STABILITY_REVIEW_REQUIRED");
        var review=reviews.get(0); JsonNode detail=readReview(review.detailJson());
        Map<String,String> expected=Map.ofEntries(Map.entry("version","UVEL_TEST_CLOUD_STABILITY_RECOVERY_V1"),
                Map.entry("deploymentScope","TEST"),Map.entry("ownerId",Long.toString(i.ownerId())),Map.entry("deviceId",Long.toString(i.deviceId())),
                Map.entry("instanceNo",i.instanceNo()),Map.entry("taskNo",taskNo),Map.entry("executorId",i.executorId()),
                Map.entry("taskConfigId",i.taskConfigId()),Map.entry("nextKeyHash",e.nextKeyHash()),Map.entry("inputHash",e.inputHash()),
                Map.entry("resultHash",Objects.toString(resultHash,"")),Map.entry("model",e.modelTag()),Map.entry("modelDigest",e.modelDigest()),
                Map.entry("proofNonceHash",sha256(e.proofNonce())),Map.entry("completeKeyHash",sha256(completeKey)));
        if(review.id()<=0 || reviewId!=null && review.id()!=reviewId || review.traceId()==null || review.traceId().isBlank()
                || review.createdAt()==null || review.createdAt().isBefore(e.createdAt()) || review.createdAt().isAfter(now().plusSeconds(1))
                || !detail.path("rollbackConfirmed").isBoolean() || !detail.path("rollbackConfirmed").booleanValue()
                || !detail.path("maxAttempts").isIntegralNumber() || detail.path("maxAttempts").asInt()!=1
                || !scope.originalHash().equals(detail.path("originalRequestHash").asText())
                || originalHash!=null && !originalHash.equals(detail.path("originalRequestHash").asText())
                || nextKey!=null && !sha256(nextKey).equals(e.nextKeyHash())
                || expected.entrySet().stream().anyMatch(field->!field.getValue().equals(detail.path(field.getKey()).asText())))
            throw error(409,"TEST_CLOUD_STABILITY_REVIEW_INVALID");
        for(String field:List.of("evidenceSha256","sourceClosureSha256","quiescenceSha256","lockedZeroEvidenceSha256","originalCallSetSha256"))
            if(!detail.path(field).isTextual() || !detail.path(field).asText().matches("[a-f0-9]{64}")) throw error(409,"TEST_CLOUD_STABILITY_REVIEW_INVALID");
        if(requireUnused && requests.stabilityUsed(taskNo)) throw error(409,"TEST_CLOUD_STABILITY_BUDGET_EXHAUSTED");
        return review;
    }
    private JsonNode readReview(String value) {
        try { JsonNode detail=JSON.readTree(value); if(detail==null || !detail.isObject()) throw new IllegalArgumentException(); return detail; }
        catch(java.io.IOException | IllegalArgumentException invalid) { throw error(409,"TEST_CLOUD_STABILITY_REVIEW_INVALID"); }
    }

    public ApiResult<Map<String,Object>> terminateRequest(Identity i,String key,String hash,String taskNo,String token,long generation) {
        requireEnabled(); key(key); safe(taskNo,96);
        return requests.terminate(i,key,hash,taskNo,token,generation,ticket->{
            lockBinding(i,false);
            AssignmentRow task=task(i,taskNo); Execution e=mapper.lock(taskNo); requireManifest(i,e);
            if(fleet(i) && !binding(i).equals(mapper.lockBinding(i.executorId()))) throw error(401,"TEST_CLOUD_AUTH_INVALID");
            var active=assignments.lockActiveAssignment(i.ownerId(),i.deviceId(),"PRODUCTION");
            if(active==null || !taskNo.equals(active.taskNo()) || mapper.countActive(i.ownerId(),i.deviceId())!=1
                    || !Objects.equals(e.proofNonce(),task.completionNonce())
                    || e.resultHash()!=null || e.resultArtifact()!=null || e.completeKeyHash()!=null || e.completeBodyHash()!=null
                    || !Integer.valueOf(1).equals(mapper.rollbackUnconsumedTask(taskNo,i.ownerId(),i.deviceId()))
                    || !mapper.rollbackReceiptEvidence(taskNo).isEmpty() || !mapper.rollbackLedgerEvidence(taskNo).isEmpty()
                    || !mapper.rollbackEventEvidence(taskNo).isEmpty()) throw error(409,"TEST_CLOUD_TERMINATION_REQUIRES_RECONCILIATION");
            boolean leaseExpired=task.leaseExpiresAt()!=null && !task.leaseExpiresAt().isAfter(now());
            boolean proofExpired=ticket.proofTimestamp()!=null && ticket.proofTimestamp()<clock.millis()-120_000;
            proofExpired=proofExpired || task.proofExpiresAt()!=null && !task.proofExpiresAt().isAfter(now());
            if(!ticket.proofValidated() && !(ticket.reviewedTermination() && leaseExpired))
                throw error(409,"TEST_CLOUD_TERMINATION_NOT_AUTHORIZED");
            if(!leaseExpired && !proofExpired) throw error(409,"TEST_CLOUD_TERMINATION_NOT_EXPIRED");
            String terminal;
            if(leaseExpired) {
                if(!settlement.expirePendingLease(i.ownerId(),i.deviceId(),taskNo)) throw error(409,"TEST_CLOUD_TERMINATION_CONFLICT");
                terminal="EXPIRED";
            } else {
                if(mapper.cancelRolledBack(taskNo,i.ownerId(),i.deviceId(),now())!=1) throw error(409,"TEST_CLOUD_TERMINATION_CONFLICT");
                assignments.clearRuntimeTask(i.ownerId(),i.deviceId(),taskNo,now());
                if(assignments.deactivatePendingDevice(i.ownerId(),i.deviceId(),now())>0)
                    assignments.markRuntimeDeactivated(i.ownerId(),i.deviceId(),now());
                terminal="CANCELLED";
            }
            record(i,"TEST_CLOUD_ROLLED_BACK_TASK_TERMINATED",taskNo,Map.of("requestHash",hash,"generation",generation,"taskStatus",terminal));
            Map<String,Object> result=bindings(i); result.put("state","ROLLED_BACK"); result.put("taskNo",taskNo);
            result.put("taskStatus",terminal); result.put("executionGeneration",generation+1); result.put("recoveryAllowed",false);
            return result;
        });
    }

    private void requireCompletionProof(Execution e,CompleteRequest request) {
        if(request==null || !SPEC.equals(request.specVersion()) || !e.inputHash().equals(request.inputHash())
                || !e.modelDigest().equals(request.modelDigest()) || !e.proofNonce().equals(request.proofNonce())
                || !e.claimToken().equals(request.claimToken()) || request.proofTimestamp()==null
                || request.proofTimestamp()<clock.millis()-120_000 || request.proofTimestamp()>clock.millis()+120_000)
            throw error(403,"TEST_CLOUD_PROOF_INVALID");
    }

    private void requireCompletionRuntime(Identity identity,String taskNo,AppTaskAssignmentMapper.TestWorkerRuntimeRow runtime) {
        if(runtime==null || !marker(identity).equals(runtime.agentVersion()) || !CLIENT.equals(runtime.clientName())
                || !"ONLINE".equals(runtime.onlineStatus()) || !taskNo.equals(runtime.activeTaskNo())
                || runtime.heartbeatAt()==null || runtime.heartbeatAt().isBefore(now().minusSeconds(120))
                || runtime.heartbeatAt().isAfter(now())) throw error(409,"TEST_CLOUD_HEARTBEAT_STALE");
    }
    private void requireSameActivation(Identity identity,AssignmentRow task,boolean lock) {
        var device=lock ? assignments.lockOwnedDevice(identity.ownerId(),identity.deviceId())
                : assignments.ownedDevice(identity.ownerId(),identity.deviceId());
        if(device==null || device.activatedAt()==null || task.startedAt()==null || task.startedAt().isBefore(device.activatedAt()))
            throw error(409,"TEST_CLOUD_ACTIVATION_CHANGED");
    }

    public ApiResult<Map<String,Object>> receipt(Identity identity,String taskNo) {
        return requests.outside(()->requests.db(()->receiptLocked(identity,taskNo)));
    }
    private ApiResult<Map<String,Object>> receiptLocked(Identity identity,String taskNo) {
        requireCurrent(identity);
        if(fleet(identity)) {
            Execution e=mapper.lock(safe(taskNo,96)); requireManifest(identity,e);
            var receipt=mapper.historicalReceipt(taskNo,identity.ownerId(),identity.deviceId());
            boolean found=receipt!=null && "COMPLETED".equals(receipt.status()) && receipt.receiptNo()!=null
                    && identity.taskConfigId().equals(receipt.taskConfigId()) && KIND.equals(receipt.modelName())
                    && CLIENT.equals(receipt.clientName()) && e.resultArtifact()!=null && e.resultHash()!=null
                    && sha256(e.resultArtifact()).equals(e.resultHash())
                    && domainProof(identity,e,e.resultHash()).equals(receipt.proofHash())
                    && Set.of("CREDITED","SETTLED","SUCCESS").contains(Objects.toString(receipt.earningStatus(),""));
            Map<String,Object> result=bindings(identity); result.put("found",found); result.put("taskNo",taskNo);
            result.put("inputHash",e.inputHash()); result.put("resultHash",e.resultHash());
            result.put("receiptNo",found?receipt.receiptNo():null); result.put("status",receipt==null?null:receipt.status());
            return ApiResult.ok(result);
        }
        // Owner/device/task order is retained even for reconciliation; no claim or financial mutation occurs.
        lockBinding(identity,false); AssignmentRow task=task(identity,safe(taskNo,96));
        Execution e=mapper.lock(taskNo); requireManifest(identity,e);
        return ApiResult.ok(receiptView(identity,e,task));
    }

    /** No mutation or retry follows from a negative receipt. Each incident requires its own archived,
     * trusted exhaustive two-request adjudication to authorize the worker's durable one-use budget. */
    public ApiResult<Map<String,Object>> rollbackReview(Identity identity,String taskNo,String completeKey,
            String originalNextKey,RollbackReviewRequest request) {
        return requests.outside(()->requests.db(()->rollbackReviewLocked(identity,taskNo,completeKey,originalNextKey,request)));
    }
    private ApiResult<Map<String,Object>> rollbackReviewLocked(Identity identity,String taskNo,String completeKey,
            String originalNextKey,RollbackReviewRequest request) {
        requireCurrent(identity);
        RollbackScope scope=REVIEWED_ROLLBACK_REQUESTS.get(taskNo);
        if(scope==null || identity.ownerId()!=60723153007L || identity.deviceId()==1152
                || scope.deviceId()!=identity.deviceId() || !scope.instanceNo().equals(identity.instanceNo())
                || !("fleet-"+scope.deviceId()).equals(identity.executorId()) || request==null
                || !scope.originalRequestHash().equals(request.originalRequestHash())
                || !ROLLBACK_RESULT.equals(request.resultHash())
                || !("BGE-COMPLETE-"+sha256(taskNo)).equals(completeKey)
                || originalNextKey==null || !originalNextKey.matches("BGE-NEXT-[a-f0-9]{32}")) {
            throw error(403,"TEST_CLOUD_ROLLBACK_REVIEW_INVALID");
        }
        lockBinding(identity,false);
        AssignmentRow task=task(identity,taskNo);
        Execution e=mapper.lock(taskNo); requireManifest(identity,e);
        if(!ROLLBACK_INPUT.equals(e.inputHash()) || !sha256(originalNextKey).equals(e.nextKeyHash()))
            throw error(403,"TEST_CLOUD_ROLLBACK_REVIEW_INVALID");
        AssignmentRow active=assignments.lockActiveAssignment(identity.ownerId(),identity.deviceId(),"PRODUCTION");
        if(active==null || !taskNo.equals(active.taskNo()) || mapper.countActive(identity.ownerId(),identity.deviceId())!=1)
            throw error(409,"TEST_CLOUD_ACTIVE_TASK_CONFLICT");
        requireLiveTask(identity,e,task); lockBinding(identity,true,true);
        if(e.resultHash()!=null || e.resultArtifact()!=null || e.completeKeyHash()!=null || e.completeBodyHash()!=null
                || !Integer.valueOf(1).equals(mapper.rollbackUnconsumedTask(taskNo,identity.ownerId(),identity.deviceId()))
                || !mapper.rollbackReceiptEvidence(taskNo).isEmpty() || !mapper.rollbackLedgerEvidence(taskNo).isEmpty()
                || !mapper.rollbackEventEvidence(taskNo).isEmpty()) {
            throw error(409,"TEST_CLOUD_ROLLBACK_REQUIRES_RECONCILIATION");
        }
        var reviews=mapper.rollbackReviews(taskNo,identity.ownerId());
        if(reviews.size()!=1) throw error(409,"TEST_CLOUD_ROLLBACK_REVIEW_REQUIRED");
        var review=reviews.get(0);
        JsonNode detail;
        try { detail=JSON.readTree(review.detailJson()); }
        catch(java.io.IOException | IllegalArgumentException invalid) { throw error(409,"TEST_CLOUD_ROLLBACK_REVIEW_INVALID"); }
        Map<String,String> expected=Map.ofEntries(
                Map.entry("version","UVEL_TEST_CLOUD_ROLLBACK_REVIEW_V1"),Map.entry("deploymentScope","TEST"),
                Map.entry("attributionMode","EXHAUSTIVE_TWO_REQUEST_SET"),Map.entry("ownerId",Long.toString(identity.ownerId())),
                Map.entry("deviceId",Long.toString(identity.deviceId())),Map.entry("instanceNo",identity.instanceNo()),
                Map.entry("taskNo",taskNo),Map.entry("executorId",identity.executorId()),Map.entry("taskConfigId",identity.taskConfigId()),
                Map.entry("nextKeyHash",e.nextKeyHash()),Map.entry("inputHash",e.inputHash()),Map.entry("resultHash",request.resultHash()),
                Map.entry("model",e.modelTag()),Map.entry("modelDigest",e.modelDigest()),Map.entry("proofNonceHash",sha256(e.proofNonce())),
                Map.entry("completeKeyHash",sha256(completeKey)),Map.entry("originalRequestHash",request.originalRequestHash()));
        if(detail==null || !detail.isObject() || review.id()<=0 || review.traceId()==null || review.traceId().isBlank()
                || review.createdAt()==null || review.createdAt().isBefore(e.createdAt())
                || review.createdAt().isAfter(now().plusSeconds(1))
                || !detail.path("rollbackConfirmed").isBoolean() || !detail.path("rollbackConfirmed").booleanValue()
                || !detail.path("maxAttempts").isIntegralNumber() || !detail.path("maxAttempts").canConvertToInt()
                || detail.path("maxAttempts").intValue()!=1
                || expected.entrySet().stream().anyMatch(field->!field.getValue().equals(detail.path(field.getKey()).asText()))) {
            throw error(409,"TEST_CLOUD_ROLLBACK_REVIEW_INVALID");
        }
        for(String field:List.of("evidenceSha256","requestSetSha256","serverErrorSetSha256","transactionProofSha256",
                "quiescenceSha256","lockedZeroEvidenceSha256")) {
            if(!detail.path(field).isTextual() || !detail.path(field).textValue().matches("[a-f0-9]{64}"))
                throw error(409,"TEST_CLOUD_ROLLBACK_REVIEW_INVALID");
        }
        Map<String,Object> result=bindings(identity);
        result.putAll(Map.of("taskNo",taskNo,"inputHash",e.inputHash(),"resultHash",request.resultHash(),
                "originalRequestHash",request.originalRequestHash(),"completeKeyHash",sha256(completeKey),
                "nextKeyHash",e.nextKeyHash(),"reviewId",review.id(),"evidenceSha256",detail.path("evidenceSha256").textValue(),
                "maxAttempts",1,"rollbackConfirmed",true));
        return ApiResult.ok(result);
    }

    private void lockBinding(Identity i) {
        lockBinding(i,true);
    }

    private void lockBinding(Identity i,boolean live) {
        lockBinding(i,live,false);
    }

    private void lockBinding(Identity i,boolean live,boolean completingExistingTask) {
        requireBinding(i,live,completingExistingTask,true);
    }

    private void requireBinding(Identity i,boolean live,boolean completingExistingTask,boolean lock) {
        requireCurrent(i);
        if(lock && !Long.valueOf(i.ownerId()).equals(assignments.lockProductionUser(i.ownerId()))) throw error(403,"TEST_CLOUD_BINDING_INVALID");
        var device=lock ? assignments.lockOwnedDevice(i.ownerId(),i.deviceId()) : assignments.ownedDevice(i.ownerId(),i.deviceId());
        if(device==null || i.deviceId()<=0 || !i.instanceNo().equals(device.instanceNo())
                || (!fleet(i) && (!"cloud-share".equals(device.productCode())
                || !Set.of("SHARE","CLOUD_SHARE").contains(Objects.toString(device.deviceType(),"").toUpperCase(Locale.ROOT))))) throw error(403,"TEST_CLOUD_BINDING_INVALID");
        // Reconciliation is read-only in effect and remains possible after a successful deferred deactivation.
        if(!live) return;
        if(fleet(i)) {
            if((lock ? mapper.countActive(i.ownerId(),i.deviceId()) : mapper.activeTaskNos(i.ownerId(),i.deviceId()).size())>1)
                throw error(409,"TEST_CLOUD_ACTIVE_TASK_CONFLICT");
            // Hold the reviewed admission until commit; a revocation cannot race the proof or cancellation.
            var admission=!legacyEnabled() ? null : lock
                    ? mapper.legacyAdmission(i.ownerId(),i.deviceId(),i.instanceNo(),null,i.taskConfigId())
                    : mapper.admission(i.ownerId(),i.deviceId(),i.instanceNo(),null,i.taskConfigId());
            Integer eligible=lock
                    ? mapper.liveFleetBinding(fleetCutoff(),tradeinFleetCutoff(environment),i.ownerId(),i.deviceId(),i.instanceNo(),completingExistingTask,admission!=null)
                    : mapper.fleetBinding(fleetCutoff(),tradeinFleetCutoff(environment),i.ownerId(),i.deviceId(),i.instanceNo(),completingExistingTask,admission!=null);
            if(!Integer.valueOf(1).equals(eligible))
                throw error(403,"TEST_CLOUD_BINDING_INVALID");
            return;
        }
        if(device.activatedAt()==null || !Set.of("ACTIVE","ONLINE","BUSY","RUNNING").contains(Objects.toString(device.status(),"").toUpperCase(Locale.ROOT))
                || Boolean.TRUE.equals(device.dispatchPaused()) || (device.pausedReason()!=null && !device.pausedReason().isBlank())
                || (!completingExistingTask && mapper.eligibleBinding(i.ownerId(),i.deviceId(),i.instanceNo())!=1)
                || (lock ? assignments.lockPaidCloudShareDailyNex(i.ownerId(),i.deviceId())
                         : assignments.paidCloudShareDailyNex(i.ownerId(),i.deviceId()))==null) throw error(403,"TEST_CLOUD_BINDING_INVALID");
    }

    private void requireManifest(Identity i,Execution e) {
        if(e==null || !i.executorId().equals(e.executorId()) || i.ownerId()!=e.ownerId() || i.deviceId()!=e.deviceId()
                || !i.instanceNo().equals(e.instanceNo()) || !i.taskConfigId().equals(e.taskConfigId())
                || !i.model().equals(e.modelTag()) || !i.modelDigest().equals(e.modelDigest())
                || !json(TEXTS).equals(e.inputJson()) || !sha256(json(TEXTS)).equals(e.inputHash())) throw error(403,"TEST_CLOUD_MANIFEST_INVALID");
    }

    private void requireLiveTask(Identity i,Execution e,AssignmentRow task) {
        if(task==null || i.deviceId()!=task.deviceId() || !i.taskConfigId().equals(task.taskId())
                || !KIND.equals(task.modelName()) || !CLIENT.equals(task.clientName())
                || !e.proofNonce().equals(task.completionNonce()) || !Set.of("RUNNING","CLAIMED").contains(task.status())
                || task.leaseExpiresAt()==null || !task.leaseExpiresAt().isAfter(now())
                || task.proofExpiresAt()==null || !task.proofExpiresAt().isAfter(now())) throw error(409,"TEST_CLOUD_TASK_INVALID");
    }

    private AssignmentRow task(Identity i,String taskNo) {
        return requireTask(i,assignments.lockAssignment(i.ownerId(),taskNo,"PRODUCTION"));
    }

    private AssignmentRow requireTask(Identity i,AssignmentRow task) {
        if(task==null || task.deviceId()!=i.deviceId()) throw error(404,"TEST_CLOUD_TASK_NOT_FOUND");
        return task;
    }

    private void heartbeatLocked(Identity i,String taskNo) {
        // heartbeat_at is DATETIME(0): truncate before MySQL can round a fractional value into the future.
        LocalDateTime heartbeatAt=now().withNano(0);
        mapper.ensureRuntime(i.deviceId(),marker(i),heartbeatAt);
        if(mapper.heartbeat(i.deviceId(),taskNo,marker(i),heartbeatAt)!=1) throw error(409,"TEST_CLOUD_RUNTIME_CONFLICT");
    }

    private Map<String,Object> claimView(Identity i,Execution e,AssignmentRow task) {
        Map<String,Object> result=bindings(i); result.put("taskNo",task.taskNo()); result.put("status",task.status());
        result.put("inputTexts",TEXTS); result.put("inputHash",e.inputHash()); result.put("proofNonce",e.proofNonce());
        result.put("claimToken",e.claimToken()); result.put("outputDimensions",1024);
        result.put("proofExpiresAt",epoch(task.proofExpiresAt())); result.put("leaseExpiresAt",epoch(task.leaseExpiresAt()));
        result.put("completableAt",epoch(task.startedAt().plusSeconds(task.requiredSeconds()))); return result;
    }

    private Map<String,Object> receiptView(Identity i,Execution e,AssignmentRow task) {
        String receiptNo="CTR-"+task.taskNo().substring(Math.max(0,task.taskNo().length()-32));
        var receipt=assignments.receipt(i.ownerId(),receiptNo);
        boolean found=receipt!=null && "COMPLETED".equals(task.status()) && task.taskNo().equals(receipt.taskNo())
                && Long.valueOf(i.deviceId()).equals(receipt.deviceId()) && i.instanceNo().equals(receipt.deviceInstanceNo())
                && i.taskConfigId().equals(receipt.taskId()) && KIND.equals(receipt.modelName()) && CLIENT.equals(receipt.clientName())
                && e.resultHash()!=null && e.resultArtifact()!=null && sha256(e.resultArtifact()).equals(e.resultHash())
                && domainProof(i,e,e.resultHash()).equals(receipt.proofHash())
                && Set.of("CREDITED","SETTLED","SUCCESS").contains(receipt.earningStatus());
        Map<String,Object> result=bindings(i); result.put("found",found); result.put("taskNo",task.taskNo());
        result.put("inputHash",e.inputHash()); result.put("resultHash",e.resultHash());
        result.put("receiptNo",found?receiptNo:null); result.put("status",task.status()); return result;
    }

    private String domainProof(Identity i,Execution e,String resultHash) {
        return sha256(String.join("\n",SPEC,i.executorId(),Long.toString(i.ownerId()),Long.toString(i.deviceId()),
                i.instanceNo(),e.taskNo(),i.taskConfigId(),e.proofNonce(),e.inputHash(),e.modelTag(),e.modelDigest(),resultHash));
    }

    private Map<String,Object> bindings(Identity i) {
        Map<String,Object> result=new LinkedHashMap<>(); result.put("executionKind",KIND); result.put("specVersion",SPEC);
        result.put("deploymentScope","TEST"); result.put("serverCanonical",true); result.put("executorId",i.executorId());
        result.put("ownerId",i.ownerId()); result.put("deviceId",i.deviceId()); result.put("instanceNo",i.instanceNo());
        result.put("taskConfigId",i.taskConfigId()); result.put("model",i.model()); result.put("modelDigest",i.modelDigest()); return result;
    }

    double[][] infer(Identity i) {
        URI origin=URI.create(property("ollama-origin"));
        if(!"http".equals(origin.getScheme()) || !"127.0.0.1".equals(origin.getHost()) || origin.getPort()<1
                || origin.getRawUserInfo()!=null || origin.getRawQuery()!=null || origin.getRawFragment()!=null
                || !(origin.getPath().isEmpty() || origin.getPath().equals("/"))) throw error(503,"TEST_CLOUD_MODEL_ORIGIN_INVALID");
        requireModelDigest(origin,i);
        JsonNode response=http(origin.resolve("/api/embed"),json(Map.of("model",i.model(),"input",TEXTS,"truncate",false)));
        if(!i.model().equals(response.path("model").asText())) throw error(503,"TEST_CLOUD_MODEL_RESPONSE_INVALID");
        JsonNode rows=response.path("embeddings");
        if(!rows.isArray() || rows.size()!=TEXTS.size()) throw error(503,"TEST_CLOUD_MODEL_RESPONSE_INVALID");
        double[][] output=new double[TEXTS.size()][1024];
        for(int r=0;r<output.length;r++) {
            if(!rows.get(r).isArray() || rows.get(r).size()!=1024) throw error(503,"TEST_CLOUD_MODEL_RESPONSE_INVALID");
            for(int c=0;c<1024;c++) {
                JsonNode value=rows.get(r).get(c);
                if(!value.isNumber() || !Double.isFinite(value.doubleValue())) throw error(503,"TEST_CLOUD_MODEL_RESPONSE_INVALID");
                output[r][c]=value.doubleValue();
            }
        }
        requireModelDigest(origin,i);
        return output;
    }

    private void requireModelDigest(URI origin,Identity i) {
        JsonNode tags=http(origin.resolve("/api/tags"),null);
        boolean matched=false;
        for(JsonNode tag:tags.path("models")) {
            String digest=tag.path("digest").asText(); if(!digest.startsWith("sha256:")) digest="sha256:"+digest;
            if(i.model().equals(tag.path("name").asText()) && i.modelDigest().equals(digest)) matched=true;
        }
        if(!matched) throw error(503,"TEST_CLOUD_MODEL_DIGEST_MISMATCH");
    }

    private JsonNode http(URI uri,String body) {
        try {
            long timeout=body==null?10_000:Math.max(100,Math.min(180_000,Long.parseLong(propertyOr("model-timeout-ms","180000"))));
            var builder=HttpRequest.newBuilder(uri).timeout(Duration.ofMillis(timeout));
            if(body==null) builder.GET(); else builder.header("Content-Type","application/json").POST(HttpRequest.BodyPublishers.ofString(body,StandardCharsets.UTF_8));
            // Consume the body before send returns, so the request timeout covers slow response bodies too.
            var pending=HTTP.sendAsync(builder.build(),info->new BoundedBody());
            HttpResponse<byte[]> response;
            try { response=pending.get(timeout,java.util.concurrent.TimeUnit.MILLISECONDS); }
            catch(java.util.concurrent.TimeoutException timeoutFailure) {
                pending.cancel(true); throw error(503,"TEST_CLOUD_MODEL_UNAVAILABLE");
            }
            byte[] bytes=response.body();
            if(response.statusCode()!=200) throw error(503,"TEST_CLOUD_MODEL_UNAVAILABLE");
            return JSON.readTree(bytes);
        } catch(InterruptedException interrupted) { Thread.currentThread().interrupt(); throw error(503,"TEST_CLOUD_MODEL_UNAVAILABLE"); }
        catch(java.util.concurrent.ExecutionException invalid) { throw error(503,"TEST_CLOUD_MODEL_UNAVAILABLE"); }
        catch(java.io.IOException invalid) { throw error(503,"TEST_CLOUD_MODEL_UNAVAILABLE"); }
    }

    private static final class BoundedBody implements HttpResponse.BodySubscriber<byte[]> {
        private final java.util.concurrent.CompletableFuture<byte[]> result=new java.util.concurrent.CompletableFuture<>();
        private final java.io.ByteArrayOutputStream bytes=new java.io.ByteArrayOutputStream();
        private java.util.concurrent.Flow.Subscription subscription;
        public java.util.concurrent.CompletionStage<byte[]> getBody() { return result; }
        public void onSubscribe(java.util.concurrent.Flow.Subscription value) { subscription=value; value.request(Long.MAX_VALUE); }
        public void onNext(List<java.nio.ByteBuffer> buffers) {
            for(var buffer:buffers) {
                if(bytes.size()+buffer.remaining()>262144) {
                    subscription.cancel(); result.completeExceptionally(new java.io.IOException("TEST_CLOUD_MODEL_BODY_TOO_LARGE")); return;
                }
                byte[] chunk=new byte[buffer.remaining()]; buffer.get(chunk); bytes.writeBytes(chunk);
            }
        }
        public void onError(Throwable failure) { result.completeExceptionally(failure); }
        public void onComplete() { result.complete(bytes.toByteArray()); }
    }

    @Scheduled(fixedDelayString="${nexion.compute-task.test-cloud.cleanup-ms:30000}")
    public void expireHeartbeat() {
        if(!"TEST".equals(property("deployment-scope")) || !Set.of(environment.getActiveProfiles()).equals(Set.of("dev"))) return;
        requests.outside(()->requests.db(()->{ expireHeartbeatLocked(); return null; }));
    }
    private void expireHeartbeatLocked() {
        // Keep the non-secret binding after revocation so only this executor's stale marker is retired.
        if(!"TEST".equals(property("deployment-scope")) || !Set.of(environment.getActiveProfiles()).equals(Set.of("dev"))) return;
        try {
            Identity i=configured(); mapper.expireHeartbeat(i.ownerId(),i.deviceId(),i.instanceNo(),marker(i),now(),now().minusSeconds(120));
            for(Binding b:mapper.bindings()) {
                Identity target=identity(b);
                mapper.expireHeartbeat(target.ownerId(),target.deviceId(),target.instanceNo(),marker(target),now(),now().minusSeconds(120));
            }
        }
        catch(IllegalArgumentException invalidConfig) { /* Disabled/unconfigured deployment has nothing to clean. */ }
    }

    private void record(Identity i,String action,String taskNo,Map<String,Object> detail) {
        audit.recordRequiredForTrustedActor(AuditLogWriteRequest.builder().action(action).resourceType("COMPUTE_TASK")
                .resourceId(taskNo).bizNo(taskNo).userId(i.ownerId()).actorType("TEST_CLOUD_EXECUTOR").actorUsername(i.getName())
                .result("SUCCESS").riskLevel("HIGH").detail(detail).build());
    }
    private String property(String name) { return environment.getProperty(PREFIX+name,""); }
    private String propertyOr(String name,String fallback) { return environment.getProperty(PREFIX+name,fallback); }
    private static String marker(Identity i) { return "test-cloud:"+i.executorId(); }
    private LocalDateTime now() { return LocalDateTime.now(clock); }
    private long epoch(LocalDateTime time) { return time.atZone(clock.getZone()).toInstant().toEpochMilli(); }
    private static void key(String key) { if(key==null || !key.matches("[A-Za-z0-9._:-]{8,128}")) throw error(422,"TEST_CLOUD_IDEMPOTENCY_INVALID"); }
    private static String safe(String value,int maximum) { if(value==null || !value.matches("[A-Za-z0-9._:-]{1,"+maximum+"}")) throw new IllegalArgumentException(); return value; }
    private static String random() { return UUID.randomUUID().toString().replace("-","")+UUID.randomUUID().toString().replace("-",""); }
    private static String json(Object value) { try { return JSON.writeValueAsString(value); } catch(Exception invalid) { throw new IllegalStateException("TEST_CLOUD_JSON_INVALID",invalid); } }
    static byte[] digest(byte[] bytes) { try { return MessageDigest.getInstance("SHA-256").digest(bytes); } catch(Exception impossible) { throw new IllegalStateException(impossible); } }
    public static String sha256(byte[] bytes) { return HexFormat.of().formatHex(digest(bytes)); }
    static String sha256(String text) { return sha256(text.getBytes(StandardCharsets.UTF_8)); }
    private static BizException error(int code,String message) { return new BizException(code,message); }
}
