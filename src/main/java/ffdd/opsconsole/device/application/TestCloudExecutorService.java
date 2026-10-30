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
import org.springframework.transaction.annotation.Transactional;

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

    public record Identity(String executorId,long ownerId,long deviceId,String instanceNo,String taskConfigId,
                           String model,String modelDigest) implements Principal {
        public String getName() { return "test-cloud:" + executorId; }
        public String toString() { return getName(); }
    }
    public record CompleteRequest(String specVersion,String inputHash,String modelDigest,String resultArtifactBase64,
                                  String resultHash,String proofNonce,Long proofTimestamp,String claimToken) { }
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
                        var candidate=mapper.fleetCandidate(fleetCutoff(),tradeinFleetCutoff(environment),deviceId,configured().deviceId());
                        if(candidate==null) throw new IllegalArgumentException();
                        identity=new Identity(executorId,candidate.ownerId(),deviceId,candidate.instanceNo(),
                                configured().taskConfigId(),configured().model(),configured().modelDigest());
                    }
                }
            }
            requireCurrent(identity); return identity;
        } catch (IllegalArgumentException invalid) { throw error(401,"TEST_CLOUD_AUTH_INVALID"); }
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
                var candidate=mapper.fleetCandidate(fleetCutoff(),tradeinFleetCutoff(environment),identity.deviceId(),configured().deviceId());
                if(candidate==null || candidate.ownerId()!=identity.ownerId() || !candidate.instanceNo().equals(identity.instanceNo())
                        || !configured().taskConfigId().equals(identity.taskConfigId())
                        || !configured().model().equals(identity.model()) || !configured().modelDigest().equals(identity.modelDigest())) throw new IllegalArgumentException();
            }
        } catch (IllegalArgumentException invalid) { throw error(401,"TEST_CLOUD_AUTH_INVALID"); }
    }

    private boolean fleetEnabled() { return "true".equals(property("fleet-enabled")); }
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

    @Transactional(readOnly=true)
    public ApiResult<Map<String,Object>> instances(Identity i,long afterDeviceId,int requestedLimit) {
        requireCurrent(i);
        if(!fleetIdentity().equals(i) || !fleetEnabled() || afterDeviceId<0) throw error(403,"TEST_CLOUD_FLEET_REQUIRED");
        int limit=Math.max(1,Math.min(100,requestedLimit));
        var candidates=mapper.fleetCandidates(fleetCutoff(),tradeinFleetCutoff(environment),afterDeviceId,configured().deviceId(),limit+1);
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

    @Transactional(rollbackFor=Exception.class)
    public ApiResult<Map<String,Object>> next(Identity identity,String key) {
        requireCurrent(identity); key(key); lockBinding(identity);
        if(fleet(identity)) {
            mapper.register(binding(identity),now());
            if(!binding(identity).equals(mapper.binding(identity.executorId()))) throw error(409,"TEST_CLOUD_BINDING_CONFLICT");
        }
        Execution replay = mapper.byNextKey(identity.executorId(),sha256(key));
        if (replay != null) { requireManifest(identity,replay); return ApiResult.ok(claimView(identity,replay,task(identity,replay.taskNo()))); }
        AssignmentRow active = assignments.lockActiveAssignment(identity.ownerId(),identity.deviceId(),"PRODUCTION");
        if (active != null) {
            Execution existing = mapper.lock(active.taskNo());
            if (existing != null) {
                requireManifest(identity,existing); requireLiveTask(identity,existing,active);
                return ApiResult.ok(claimView(identity,existing,active));
            }
            if (!active.taskNo().equals(property("cancel-unexecuted-task-no"))
                    || !identity.taskConfigId().equals(active.taskId())
                    || mapper.cancelUnexecuted(identity.ownerId(),identity.deviceId(),active.taskNo(),identity.taskConfigId(),now())!=1) {
                throw error(409,"TEST_CLOUD_UNMANIFESTED_TASK_BLOCKED");
            }
            assignments.clearRuntimeTask(identity.ownerId(),identity.deviceId(),active.taskNo(),now());
            record(identity,"TEST_CLOUD_UNEXECUTED_TASK_CANCELLED",active.taskNo(),Map.of("reason","No input or execution evidence; replaced by explicit TEST inference"));
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
        return ApiResult.ok(claimView(identity,e,task(identity,e.taskNo())));
    }

    @Transactional(rollbackFor=Exception.class)
    public ApiResult<Map<String,Object>> heartbeat(Identity identity,String taskNo,String claimToken) {
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

    @Transactional(rollbackFor=Exception.class)
    public ApiResult<Map<String,Object>> complete(Identity identity,String taskNo,String key,CompleteRequest request) {
        requireCurrent(identity); key(key); lockBinding(identity,false);
        AssignmentRow task=task(identity,safe(taskNo,96));
        Execution e=mapper.lock(taskNo); requireManifest(identity,e);
        String bodyHash=sha256(json(request));
        if(e.resultHash()!=null) {
            if(!Objects.equals(e.completeKeyHash(),sha256(key)) || !Objects.equals(e.completeBodyHash(),bodyHash)) {
                throw error(409,"TEST_CLOUD_COMPLETION_REPLAYED");
            }
            return ApiResult.ok(receiptView(identity,e,task));
        }
        lockBinding(identity,true,true);
        requireLiveTask(identity,e,task);
        if(request==null || !SPEC.equals(request.specVersion()) || !e.inputHash().equals(request.inputHash())
                || !e.modelDigest().equals(request.modelDigest()) || !e.proofNonce().equals(request.proofNonce())
                || !e.claimToken().equals(request.claimToken()) || request.proofTimestamp()==null
                || request.proofTimestamp()<clock.millis()-120_000 || request.proofTimestamp()>clock.millis()+120_000) throw error(403,"TEST_CLOUD_PROOF_INVALID");
        var runtime=assignments.lockTestWorkerRuntime(identity.ownerId(),identity.deviceId(),identity.instanceNo());
        if(runtime==null || !marker(identity).equals(runtime.agentVersion()) || !CLIENT.equals(runtime.clientName())
                || !"ONLINE".equals(runtime.onlineStatus()) || !taskNo.equals(runtime.activeTaskNo())
                || runtime.heartbeatAt()==null || runtime.heartbeatAt().isBefore(now().minusSeconds(120))
                || runtime.heartbeatAt().isAfter(now())) throw error(409,"TEST_CLOUD_HEARTBEAT_STALE");
        TestCloudBgeArtifact.decode(request.resultArtifactBase64(),request.resultHash());
        // ponytail: three short TEST texts; bounded recomputation under the same locks avoids a proof/settlement TOCTOU.
        double[][] independentlyComputed=infer(identity);
        byte[] artifact=TestCloudBgeArtifact.verify(request.resultArtifactBase64(),request.resultHash(),independentlyComputed);
        requireCurrent(identity); requireLiveTask(identity,e,task(identity,taskNo));
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

    @Transactional(rollbackFor=Exception.class)
    public ApiResult<Map<String,Object>> receipt(Identity identity,String taskNo) {
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

    private void lockBinding(Identity i) {
        lockBinding(i,true);
    }

    private void lockBinding(Identity i,boolean live) {
        lockBinding(i,live,false);
    }

    private void lockBinding(Identity i,boolean live,boolean completingExistingTask) {
        requireCurrent(i);
        if(!Long.valueOf(i.ownerId()).equals(assignments.lockProductionUser(i.ownerId()))) throw error(403,"TEST_CLOUD_BINDING_INVALID");
        var device=assignments.lockOwnedDevice(i.ownerId(),i.deviceId());
        if(device==null || i.deviceId()<=0 || !i.instanceNo().equals(device.instanceNo())
                || (!fleet(i) && (!"cloud-share".equals(device.productCode())
                || !Set.of("SHARE","CLOUD_SHARE").contains(Objects.toString(device.deviceType(),"").toUpperCase(Locale.ROOT))))) throw error(403,"TEST_CLOUD_BINDING_INVALID");
        // Reconciliation is read-only in effect and remains possible after a successful deferred deactivation.
        if(!live) return;
        if(fleet(i)) {
            if(!Integer.valueOf(1).equals(mapper.liveFleetBinding(fleetCutoff(),tradeinFleetCutoff(environment),i.ownerId(),i.deviceId(),i.instanceNo(),completingExistingTask)))
                throw error(403,"TEST_CLOUD_BINDING_INVALID");
            return;
        }
        if(device.activatedAt()==null || !Set.of("ACTIVE","ONLINE","BUSY","RUNNING").contains(Objects.toString(device.status(),"").toUpperCase(Locale.ROOT))
                || Boolean.TRUE.equals(device.dispatchPaused()) || (device.pausedReason()!=null && !device.pausedReason().isBlank())
                || (!completingExistingTask && mapper.eligibleBinding(i.ownerId(),i.deviceId(),i.instanceNo())!=1)
                || assignments.lockPaidCloudShareDailyNex(i.ownerId(),i.deviceId())==null) throw error(403,"TEST_CLOUD_BINDING_INVALID");
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
        var task=assignments.lockAssignment(i.ownerId(),taskNo,"PRODUCTION");
        if(task==null || task.deviceId()!=i.deviceId()) throw error(404,"TEST_CLOUD_TASK_NOT_FOUND");
        return task;
    }

    private void heartbeatLocked(Identity i,String taskNo) {
        mapper.ensureRuntime(i.deviceId(),marker(i),now());
        if(mapper.heartbeat(i.deviceId(),taskNo,marker(i),now())!=1) throw error(409,"TEST_CLOUD_RUNTIME_CONFLICT");
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
    @Transactional
    public void expireHeartbeat() {
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
