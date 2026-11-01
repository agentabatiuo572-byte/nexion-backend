package ffdd.opsconsole.device.application;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.MapperFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.json.JsonMapper;
import ffdd.opsconsole.common.boundary.ApplicationService;
import ffdd.opsconsole.device.dto.LegacyUnknownZeroCreditProof;
import ffdd.opsconsole.device.infrastructure.LegacyUnknownQuiescenceVerifier;
import ffdd.opsconsole.device.mapper.AppTaskAssignmentMapper;
import ffdd.opsconsole.device.mapper.LegacyUnknownZeroCreditMapper;
import ffdd.opsconsole.platform.application.A2ReplayContext;
import ffdd.opsconsole.platform.domain.AuditReplayCommand;
import ffdd.opsconsole.shared.audit.AuditLogService;
import ffdd.opsconsole.shared.audit.AuditLogWriteRequest;
import ffdd.opsconsole.shared.exception.BizException;
import ffdd.opsconsole.shared.outbox.EventOutboxService;
import java.time.Clock;
import java.time.LocalDateTime;
import java.util.*;
import java.util.concurrent.atomic.AtomicReference;
import lombok.RequiredArgsConstructor;
import org.springframework.core.env.Environment;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.transaction.annotation.Transactional;

@ApplicationService
@RequiredArgsConstructor
public class LegacyUnknownZeroCreditService {
    public record Command(long deviceId,String caseId,LegacyUnknownZeroCreditProof proof) { }
    private record Registered(LegacyUnknownZeroCreditProof proof,String sha) { }
    private static final JsonMapper COMMAND_JSON=JsonMapper.builder()
            .enable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES,DeserializationFeature.FAIL_ON_NULL_FOR_PRIMITIVES,
                    DeserializationFeature.FAIL_ON_MISSING_CREATOR_PROPERTIES)
            .disable(MapperFeature.ALLOW_COERCION_OF_SCALARS).disable(DeserializationFeature.ACCEPT_FLOAT_AS_INT).build();
    private final LegacyUnknownZeroCreditMapper mapper;
    private final LegacyUnknownQuiescenceVerifier verifier;
    private final AppTaskAssignmentMapper assignments;
    private final AuditLogService audit;
    private final EventOutboxService outbox;
    private final ObjectMapper json;
    private final Environment environment;
    private final Clock clock;
    private final AtomicReference<Registered> registered=new AtomicReference<>();

    public Map<String,Object> info() {
        requireScope();var p=registered.get();
        var result=new LinkedHashMap<String,Object>();
        result.put("backendInstanceId",verifier.backendInstanceId());
        result.put("caseSetSha256",LegacyUnknownZeroCreditCases.MANIFEST_SHA);
        result.put("caseManifest",LegacyUnknownZeroCreditCases.MANIFEST_JSON);
        result.put("cases",LegacyUnknownZeroCreditCases.ALL);
        result.put("proofSha",p==null?null:p.sha());return result;
    }
    public Map<String,Object> register(LegacyUnknownZeroCreditProof proof) {
        var observation=verifier.verify(proof);String sha=LegacyUnknownZeroCreditCases.sha(proof.observation());
        registered.set(new Registered(proof,sha));
        return Map.of("registered",true,"proofSha",sha,"backendInstanceId",verifier.backendInstanceId(),
                "caseSetSha256",LegacyUnknownZeroCreditCases.MANIFEST_SHA,"expiresAt",observation.expiresAt());
    }
    public Map<String,Object> candidate(long deviceId) {
        requireScope();
        if (!LegacyUnknownZeroCreditCases.contains(deviceId))
            return Map.of("eligible",false,"reason","LEGACY_UNKNOWN_CASE_NOT_AUTHORIZED","deviceId",deviceId);
        var c=LegacyUnknownZeroCreditCases.require(deviceId);var result=new LinkedHashMap<String,Object>();
        result.put("caseId",c.caseId());result.put("taskNo",c.taskNo());result.put("ownerId",c.ownerId());
        result.put("deviceId",deviceId);result.put("instanceNo",c.instanceNo());result.put("requestGeneration",c.requestGeneration());
        result.put("requestKeyHash",c.requestKeyHash());result.put("requestBodyHash",c.requestBodyHash());
        result.put("caseSetSha256",LegacyUnknownZeroCreditCases.MANIFEST_SHA);result.put("backendInstanceId",verifier.backendInstanceId());
        var previous=mapper.resolution(c.caseId());
        result.put("resolution",null);
        result.put("eligible",false);result.put("proofSha",null);
        try {
            if (previous!=null) {
                validatePrior(c,previous);result.put("resolution",resolution(previous));
                result.put("status","RESOLVED");result.put("reason","LEGACY_UNKNOWN_ALREADY_RESOLVED");return result;
            }
            evidence(c,mapper.evidence(c.ownerId(),deviceId,c.taskNo()));
            requireZero(c);
            var proof=currentProof(null);result.put("proofSha",proof.sha());
            result.put("eligible",true);result.put("status","ELIGIBLE");result.put("reason","");
        } catch (BizException blocked) {
            result.put("status",blocked.getMessage().contains("QUIESCENCE")?"QUIESCENCE_REQUIRED":"BLOCKED");
            result.put("reason",blocked.getMessage());
        }
        return result;
    }
    public AuditReplayCommand proposal(long deviceId,String proofSha) {
        requireScope();var c=LegacyUnknownZeroCreditCases.require(deviceId);var p=currentProof(proofSha);
        var eligible=candidate(deviceId);
        if (!Boolean.TRUE.equals(eligible.get("eligible"))) throw denied(Objects.toString(eligible.get("reason")));
        return new AuditReplayCommand("E",LegacyUnknownZeroCreditCases.OP,
                Map.of("deviceId",deviceId,"caseId",c.caseId(),"proof",Map.of("observation",p.proof().observation(),"signature",p.proof().signature())));
    }
    public void validateProposal(Command command) {
        var p=currentProof(LegacyUnknownZeroCreditCases.sha(command.proof().observation()));
        if (!p.proof().equals(command.proof())) throw denied("LEGACY_UNKNOWN_QUIESCENCE_REQUIRED");
        var candidate=candidate(command.deviceId());
        if (!Boolean.TRUE.equals(candidate.get("eligible"))) throw denied(Objects.toString(candidate.get("reason")));
    }
    public void validateApproval(Command command) {
        var c=LegacyUnknownZeroCreditCases.require(command.deviceId());var prior=mapper.resolution(c.caseId());
        if (prior!=null) { validatePrior(c,prior);return; }
        verifier.verify(command.proof());
    }
    public static Command command(Map<String,Object> params) {
        try {
            Command c=COMMAND_JSON.convertValue(params,Command.class);
            if (!LegacyUnknownZeroCreditCases.require(c.deviceId()).caseId().equals(c.caseId())
                    || c.proof()==null || c.proof().observation()==null || c.proof().signature()==null) throw denied("LEGACY_UNKNOWN_COMMAND_INVALID");
            return c;
        } catch (RuntimeException malformed) { throw denied("LEGACY_UNKNOWN_COMMAND_INVALID"); }
    }
    public static String proofEpoch(Command command) {
        try {
            var epoch=COMMAND_JSON.readTree(command.proof().observation()).get("backendInstanceId");
            if (epoch==null || !epoch.isTextual()) throw denied("LEGACY_UNKNOWN_COMMAND_INVALID");
            return UUID.fromString(epoch.textValue()).toString();
        } catch (Exception invalid) { throw denied("LEGACY_UNKNOWN_COMMAND_INVALID"); }
    }

    @Transactional
    public Map<String,Object> approve(Command command) {
        requireScope();requireApproval();
        var c=LegacyUnknownZeroCreditCases.require(command.deviceId());
        if (!c.caseId().equals(command.caseId())) throw denied("LEGACY_UNKNOWN_COMMAND_INVALID");
        if (!Integer.valueOf(1).equals(mapper.lockResourceGuard()) || !Long.valueOf(c.ownerId()).equals(mapper.lockOwner(c.ownerId())))
            throw denied("LEGACY_UNKNOWN_OWNER_INVALID");
        var device=mapper.lockDevice(c.ownerId(),c.deviceId());
        if (device==null || !c.instanceNo().equals(device.get("instanceNo"))) throw denied("LEGACY_UNKNOWN_IDENTITY_CHANGED");
        var previous=mapper.resolution(c.caseId());
        if (previous!=null) { validatePrior(c,previous);return resolution(previous); }
        var observation=verifier.verify(command.proof());
        var original=evidence(c,mapper.lockEvidence(c.ownerId(),c.deviceId(),c.taskNo()));
        var runtime=mapper.lockRuntime(c.deviceId());
        if (runtime!=null && runtime.get("activeTaskNo")!=null
                && (!c.taskNo().equals(runtime.get("activeTaskNo")) || !("test-cloud:"+c.executorId()).equals(runtime.get("agentVersion"))
                    || !TestCloudExecutorService.CLIENT.equals(runtime.get("clientName")))) throw denied("LEGACY_UNKNOWN_RUNTIME_CHANGED");
        if (mapper.lockActiveTasks(c.ownerId(),c.deviceId()).stream().anyMatch(t -> !c.taskNo().equals(t)))
            throw denied("LEGACY_UNKNOWN_RUNTIME_CHANGED");
        requireZero(c);
        LocalDateTime now=LocalDateTime.now(clock);
        if (mapper.cancelTask(c.ownerId(),c.deviceId(),c.taskNo(),now)!=1) throw denied("LEGACY_UNKNOWN_TASK_CHANGED");
        if (runtime!=null && runtime.get("activeTaskNo")!=null
                && mapper.clearRuntime(c.deviceId(),c.taskNo(),"test-cloud:"+c.executorId(),now)!=1)
            throw denied("LEGACY_UNKNOWN_RUNTIME_CHANGED");
        boolean deactivated=false;
        if (number(device.get("pendingDeactivate"))==1) {
            if (assignments.deactivatePendingDevice(c.ownerId(),c.deviceId(),now)!=1) throw denied("LEGACY_UNKNOWN_DEVICE_CHANGED");
            assignments.markRuntimeDeactivated(c.ownerId(),c.deviceId(),now);deactivated=true;
            var attribution=assignments.userEventAttribution(c.ownerId());
            if (attribution==null) throw denied("LEGACY_UNKNOWN_EVENT_ATTRIBUTION_MISSING");
            outbox.publishUserEvent("USER_DEVICE",c.instanceNo(),"device.deactivated",c.ownerId(),attribution.phase(),
                    attribution.accountAgeMonths(),attribution.cohort(),Map.of("deviceId",c.deviceId(),"instanceNo",c.instanceNo(),
                        "status","DEACTIVATED","trigger","UNKNOWN_ABANDONED_NO_CREDIT"));
        }
        var snapshot=new LinkedHashMap<String,Object>();snapshot.put("case",c);snapshot.put("requestTaskExecution",original);
        snapshot.put("runtime",runtime);snapshot.put("device",device);snapshot.put("proof",command.proof());
        snapshot.put("deviceDeactivated",deactivated);
        var values=new LinkedHashMap<String,Object>(original);
        values.put("caseId",c.caseId());values.put("caseSetSha256",LegacyUnknownZeroCreditCases.MANIFEST_SHA);
        values.put("quiescenceSha256",LegacyUnknownZeroCreditCases.sha(command.proof().observation()));
        values.put("backendInstanceId",observation.backendInstanceId());values.put("a2OperationId",A2ReplayContext.operationId());
        values.put("snapshotJson",encode(snapshot));values.put("now",now);
        if (mapper.insertResolution(values)!=1) throw denied("LEGACY_UNKNOWN_RESOLUTION_CONFLICT");
        audit.recordRequired(AuditLogWriteRequest.builder().action("LEGACY_UNKNOWN_ZERO_CREDIT_RESOLVED")
                .resourceType("COMPUTE_TASK").resourceId(c.taskNo()).userId(c.ownerId()).actorType("ADMIN")
                .actorUsername(SecurityContextHolder.getContext().getAuthentication().getName()).result("SUCCESS").riskLevel("HIGH")
                .detail(Map.of("caseId",c.caseId(),"a2OperationId",A2ReplayContext.operationId(),"proofSha",values.get("quiescenceSha256"),
                        "outcome","ZERO_CREDIT_CANCELLED","credited",false,"deviceDeactivated",deactivated)).build());
        return resolution(mapper.resolution(c.caseId()));
    }
    private Map<String,Object> evidence(LegacyUnknownZeroCreditCases.Case c,List<Map<String,Object>> rows) {
        if (rows==null || rows.size()!=1) throw denied("LEGACY_UNKNOWN_IDENTITY_CHANGED");
        var r=rows.get(0);
        if (!c.executorId().equals(r.get("executorId")) || !"COMPLETE".equals(r.get("operation"))
                || !c.requestKeyHash().equals(r.get("requestKeyHash")) || !c.requestBodyHash().equals(r.get("requestBodyHash"))
                || number(r.get("ownerId"))!=c.ownerId() || number(r.get("deviceId"))!=c.deviceId()
                || !c.instanceNo().equals(r.get("instanceNo")) || !c.taskNo().equals(r.get("taskNo"))
                || number(r.get("requestGeneration"))!=1 || number(r.get("recoveryUsed"))!=0 || number(r.get("proofValidated"))!=1
                || !Set.of("PROCESSING","UNKNOWN").contains(r.get("requestState")) || r.get("responseJson")!=null
                || !c.taskConfigId().equals(r.get("taskConfigId")) || !c.taskConfigId().equals(r.get("taskConfig"))
                || number(r.get("taskOwner"))!=c.ownerId() || number(r.get("taskDevice"))!=c.deviceId()
                || !"PRODUCTION".equals(r.get("sourceEnvironment")) || number(r.get("taskDeleted"))!=0
                || !Set.of("CLAIMED","RUNNING","EXPIRED").contains(r.get("taskStatus"))
                || r.get("proofConsumedAt")!=null || r.get("completedAt")!=null
                || !TestCloudExecutorService.KIND.equals(r.get("modelName")) || !TestCloudExecutorService.CLIENT.equals(r.get("clientName"))
                || !c.executorId().equals(r.get("executionExecutor")) || number(r.get("executionOwner"))!=c.ownerId()
                || number(r.get("executionDevice"))!=c.deviceId() || !c.instanceNo().equals(r.get("executionInstance"))
                || !c.inputHash().equals(r.get("inputHash")) || !c.modelDigest().equals(r.get("modelDigest"))
                || !c.modelTag().equals(r.get("modelTag")) || !c.nextKeyHash().equals(r.get("nextKeyHash"))
                || !c.taskConfigId().equals(r.get("executionConfig")) || r.get("resultHash")!=null
                || number(r.get("hasResultArtifact"))!=0 || r.get("completeKeyHash")!=null || r.get("completeBodyHash")!=null)
            throw denied("LEGACY_UNKNOWN_IDENTITY_OR_EXECUTION_CHANGED");
        return r;
    }
    private void requireZero(LegacyUnknownZeroCreditCases.Case c) {
        String receipt="CTR-"+c.taskNo().substring(c.taskNo().length()-32);
        if (!mapper.receipts(c.taskNo(),receipt).isEmpty() || !mapper.earnings(c.taskNo(),receipt).isEmpty()
                || !mapper.ledgers(c.taskNo(),receipt).isEmpty()
                || !mapper.unattributedDailyLedgers(c.ownerId(),c.deviceId(),c.taskNo()).isEmpty()
                || !mapper.fundOperations(SupportDeviceFundAdapter.key(receipt),receipt).isEmpty()
                || !mapper.completionOutbox(c.taskNo()).isEmpty() || !mapper.completionAudit(c.taskNo(),receipt).isEmpty()
                || !mapper.archivedCompletionAudit(c.taskNo(),receipt).isEmpty()) throw denied("LEGACY_UNKNOWN_FINANCIAL_EVIDENCE_EXISTS");
    }
    private Registered currentProof(String sha) {
        var p=registered.get();
        if (p==null || sha!=null && !p.sha().equals(sha)) throw denied("LEGACY_UNKNOWN_QUIESCENCE_REQUIRED");
        verifier.verify(p.proof());return p;
    }
    private void requireScope() {
        if (!"TEST".equals(environment.getProperty("nexion.compute-v2.deployment-scope"))) throw denied("LEGACY_UNKNOWN_TEST_SCOPE_REQUIRED");
    }
    private void requireApproval() {
        var a=SecurityContextHolder.getContext().getAuthentication();
        if (!A2ReplayContext.isReplaying() || A2ReplayContext.operationId()==null || a==null || !a.isAuthenticated()
                || !(a.getDetails() instanceof Map<?,?> details) || !"ADMIN".equals(details.get("subjectType"))
                || !a.getAuthorities().stream().anyMatch(v -> "device_e5_write".equals(v.getAuthority()))
                || !a.getAuthorities().stream().anyMatch(v -> "platform_a2_operation_approve".equals(v.getAuthority())))
            throw new BizException(403,"LEGACY_UNKNOWN_A2_APPROVAL_REQUIRED");
    }
    private Map<String,Object> resolution(Map<String,Object> row) {
        if (row==null) throw denied("LEGACY_UNKNOWN_RESOLUTION_MISSING");
        var result=new LinkedHashMap<String,Object>();
        for (String key:List.of("resolutionId","caseId","taskNo","deviceId","outcome","taskStatus","a2OperationId","resolvedAt"))
            result.put(key,row.get(key));
        result.put("credited",false);return result;
    }
    private void validatePrior(LegacyUnknownZeroCreditCases.Case c,Map<String,Object> r) {
        if (!c.caseId().equals(r.get("caseId")) || !c.taskNo().equals(r.get("taskNo"))
                || number(r.get("ownerId"))!=c.ownerId() || number(r.get("deviceId"))!=c.deviceId()
                || !c.executorId().equals(r.get("executorId")) || !c.instanceNo().equals(r.get("instanceNo"))
                || !c.taskConfigId().equals(r.get("taskConfigId")) || !"COMPLETE".equals(r.get("operation"))
                || !c.requestKeyHash().equals(r.get("requestKeyHash")) || !c.requestBodyHash().equals(r.get("requestBodyHash"))
                || number(r.get("requestGeneration"))!=1 || !"TEST".equals(r.get("deploymentScope"))
                || !LegacyUnknownZeroCreditCases.MANIFEST_SHA.equals(r.get("caseSetSha256"))
                || !"ZERO_CREDIT_CANCELLED".equals(r.get("outcome")) || !"CANCELLED".equals(r.get("taskStatus"))
                || mapper.validPrior(number(r.get("requestId")),c.inputHash(),c.modelTag(),c.modelDigest(),c.nextKeyHash())!=1)
            throw denied("LEGACY_UNKNOWN_RESOLUTION_INVALID");
    }
    private String encode(Object value) {
        try { return json.writeValueAsString(value); }
        catch (Exception invalid) { throw new IllegalStateException("Legacy cancellation snapshot serialization failed",invalid); }
    }
    private static long number(Object value) {
        if (value instanceof Boolean b) return b?1:0;
        return value instanceof Number n?n.longValue():Long.MIN_VALUE;
    }
    private static BizException denied(String reason) { return new BizException(409,reason); }
}
