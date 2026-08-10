package ffdd.opsconsole.janus.application;

import ffdd.opsconsole.janus.domain.JanusRemoteTargetRepository;
import ffdd.opsconsole.janus.domain.JanusRemoteTargetView;
import ffdd.opsconsole.janus.dto.JanusTakeoverAdminRequest;
import ffdd.opsconsole.janus.dto.JanusTakeoverProgressRequest;
import ffdd.opsconsole.janus.mapper.JanusTakeoverMapper;
import ffdd.opsconsole.shared.api.ApiResult;
import ffdd.opsconsole.shared.audit.AuditLogService;
import ffdd.opsconsole.shared.audit.AuditLogWriteRequest;
import ffdd.opsconsole.shared.idempotency.AdminIdempotencyService;
import ffdd.opsconsole.shared.security.AdminActorResolver;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Supplier;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

@Service
@RequiredArgsConstructor
public class JanusTakeoverService {
    private static final Set<String> FAILURE_CLASSES = Set.of("delivery","target","webview","handoff","lease","cleanup","contract");
    private static final Map<String,Set<String>> NEXT = Map.ofEntries(
            Map.entry("COMMAND_PENDING_ACK",Set.of("RECEIVED","FAILED","CANCELLED")),
            Map.entry("RECEIVED",Set.of("WAITING_SESSION_EDGE","LOADING","FAILED")),
            Map.entry("WAITING_SESSION_EDGE",Set.of("LOADING","FAILED","CANCELLED")),
            Map.entry("LOADING",Set.of("HANDOFF_FETCHING","FAILED")),
            Map.entry("HANDOFF_FETCHING",Set.of("HANDOFF_MERGING","FAILED")),
            Map.entry("HANDOFF_MERGING",Set.of("HANDOFF_ACKED","FAILED")),
            Map.entry("HANDOFF_ACKED",Set.of("SUCCEEDED","FAILED")),
            Map.entry("REVOKE_PENDING_ACK",Set.of("REVOKED","REVOKE_FAILED")));

    private final JanusTakeoverMapper mapper;
    private final JanusRemoteTargetRepository targetRepository;
    private final AuditLogService audit;
    private final AdminIdempotencyService idempotency;

    public Map<String,Object> view(String sid) {
        Map<String,Object> row=mapper.find(sid);
        return row==null?null:new LinkedHashMap<>(row);
    }

    @Transactional
    public void activate(String sid,String commandId,String targetId,Integer targetVersion,Long catalogVersion,
                         String causeRequestId,String causeDecisionId) {
        if (!validTarget(targetId,targetVersion,catalogVersion)) throw new IllegalStateException("JANUS_REMOTE_TARGET_UNAVAILABLE");
        Map<String,Object> current = mapper.findForUpdate(sid);
        if (current != null && commandId.equals(text(current,"commandId"))
                && "ACTIVATE".equals(text(current,"commandType"))) return;
        mapper.activate(sid,commandId,targetId,targetVersion,catalogVersion,causeRequestId,causeDecisionId);
    }

    public ApiResult<Map<String,Object>> revoke(String sid,String idempotencyKey,JanusTakeoverAdminRequest request) {
        String invalid=validateAdmin(sid,idempotencyKey,request);
        if(invalid!=null)return ApiResult.fail(422,invalid);
        return once("REVOKE",sid,idempotencyKey,request,()->revokeOnce(sid.trim(),idempotencyKey.trim(),request));
    }

    private ApiResult<Map<String,Object>> revokeOnce(String sid,String idempotencyKey,JanusTakeoverAdminRequest request) {
        Map<String,Object> row=mapper.findForUpdate(sid);
        if(row==null)return ApiResult.fail(404,"K6_TAKEOVER_NOT_FOUND");
        if(idempotencyKey.equals(text(row,"commandId"))&&"REVOKE".equals(text(row,"commandType")))return ApiResult.ok(row);
        if(!List.of("COMMAND_PENDING_ACK","RECEIVED","WAITING_SESSION_EDGE","LOADING","HANDOFF_FETCHING","HANDOFF_MERGING","HANDOFF_ACKED","SUCCEEDED","FAILED","REVOKE_FAILED").contains(text(row,"phase")))return ApiResult.fail(409,"K6_TAKEOVER_REVOKE_NOT_ALLOWED");
        long version=num(row,"rowVersion");
        if(request.expectedVersion()!=version)return ApiResult.fail(409,"K6_TAKEOVER_VERSION_CONFLICT");
        if(mapper.replaceCommand(sid,version,"REVOKE_PENDING_ACK",idempotencyKey,"REVOKE",textOrNull(row,"expectedTargetId"),intOrNull(row,"expectedTargetVersion"),longOrNull(row,"expectedTargetCatalogVersion"),idempotencyKey)!=1)return ApiResult.fail(409,"K6_TAKEOVER_VERSION_CONFLICT");
        Map<String,Object> after=mapper.find(sid); audit("K6_TAKEOVER_REVOKE_REQUESTED",sid,request,after); return ApiResult.ok(after);
    }

    public ApiResult<Map<String,Object>> resendRevoke(String sid,String idempotencyKey,JanusTakeoverAdminRequest request) {
        String invalid=validateAdmin(sid,idempotencyKey,request);if(invalid!=null)return ApiResult.fail(422,invalid);
        return once("REVOKE_RESEND",sid,idempotencyKey,request,()->resendRevokeOnce(sid.trim(),request));
    }

    private ApiResult<Map<String,Object>> resendRevokeOnce(String sid,JanusTakeoverAdminRequest request) {
        Map<String,Object> row=mapper.findForUpdate(sid);if(row==null)return ApiResult.fail(404,"K6_TAKEOVER_NOT_FOUND");
        long version=num(row,"rowVersion"); if(request.expectedVersion()!=version)return ApiResult.fail(409,"K6_TAKEOVER_VERSION_CONFLICT");
        if(mapper.resendRevoke(sid,version)!=1)return ApiResult.fail(409,"K6_REVOKE_RESEND_NOT_ALLOWED");
        Map<String,Object> after=mapper.find(sid);audit("K6_TAKEOVER_REVOKE_RESENT",sid,request,after);return ApiResult.ok(after);
    }

    public ApiResult<Map<String,Object>> changeTarget(String sid,String idempotencyKey,JanusTakeoverAdminRequest request) {
        String invalid=validateAdmin(sid,idempotencyKey,request);if(invalid!=null)return ApiResult.fail(422,invalid);
        if(!validTarget(request.targetId(),request.targetVersion(),request.targetCatalogVersion()))return ApiResult.fail(422,"JANUS_REMOTE_TARGET_UNAVAILABLE");
        return once("CHANGE_TARGET",sid,idempotencyKey,request,()->changeTargetOnce(sid.trim(),idempotencyKey.trim(),request));
    }

    private ApiResult<Map<String,Object>> changeTargetOnce(String sid,String idempotencyKey,JanusTakeoverAdminRequest request) {
        Map<String,Object> row=mapper.findForUpdate(sid);if(row==null)return ApiResult.fail(404,"K6_TAKEOVER_NOT_FOUND");
        if(idempotencyKey.equals(text(row,"commandId"))&&"CHANGE_TARGET".equals(text(row,"commandType")))return ApiResult.ok(row);
        long version=num(row,"rowVersion");if(request.expectedVersion()!=version)return ApiResult.fail(409,"K6_TAKEOVER_VERSION_CONFLICT");
        if(mapper.replaceCommand(sid,version,"COMMAND_PENDING_ACK",idempotencyKey,"CHANGE_TARGET",request.targetId().trim(),request.targetVersion(),request.targetCatalogVersion(),idempotencyKey)!=1)return ApiResult.fail(409,"K6_TAKEOVER_VERSION_CONFLICT");
        Map<String,Object> after=mapper.find(sid);audit("K6_TAKEOVER_TARGET_CHANGED",sid,request,after);return ApiResult.ok(after);
    }

    public ApiResult<Map<String,Object>> retry(String sid,String idempotencyKey,JanusTakeoverAdminRequest request) {
        String invalid=validateAdmin(sid,idempotencyKey,request);if(invalid!=null)return ApiResult.fail(422,invalid);
        return once("RETRY",sid,idempotencyKey,request,()->retryOnce(sid.trim(),idempotencyKey.trim(),request));
    }

    private ApiResult<Map<String,Object>> retryOnce(String sid,String idempotencyKey,JanusTakeoverAdminRequest request) {
        Map<String,Object> row=mapper.findForUpdate(sid);if(row==null)return ApiResult.fail(404,"K6_TAKEOVER_NOT_FOUND");
        if(!"FAILED".equals(text(row,"phase"))||!Set.of("delivery","webview").contains(text(row,"failureClass")))return ApiResult.fail(409,"K6_TAKEOVER_RETRY_NOT_ALLOWED");
        long version=num(row,"rowVersion");if(request.expectedVersion()!=version)return ApiResult.fail(409,"K6_TAKEOVER_VERSION_CONFLICT");
        if(mapper.replaceCommand(sid,version,"COMMAND_PENDING_ACK",idempotencyKey,"ACTIVATE",textOrNull(row,"expectedTargetId"),intOrNull(row,"expectedTargetVersion"),longOrNull(row,"expectedTargetCatalogVersion"),idempotencyKey)!=1)return ApiResult.fail(409,"K6_TAKEOVER_VERSION_CONFLICT");
        Map<String,Object> after=mapper.find(sid);audit("K6_TAKEOVER_RETRIED",sid,request,after);return ApiResult.ok(after);
    }

    public ApiResult<Map<String,Object>> applied(String sid,String reconciliationId) {
        if(!StringUtils.hasText(sid))return ApiResult.fail(422,"SID_REQUIRED");
        Map<String,Object> row=mapper.find(sid.trim());if(row==null)return ApiResult.fail(404,"K6_TAKEOVER_NOT_FOUND");
        if(StringUtils.hasText(reconciliationId)&&!reconciliationId.trim().equals(text(row,"reconciliationId"))){return ApiResult.fail(409,"K6_RECONCILIATION_ID_CONFLICT");}
        Map<String,Object> result=new LinkedHashMap<>(row);
        result.put("fresh",StringUtils.hasText(text(row,"reconciliationId"))&&row.get("reconciledAt")!=null);
        return ApiResult.ok(result);
    }

    public ApiResult<Map<String,Object>> requestApplied(String sid,String idempotencyKey,JanusTakeoverAdminRequest request) {
        String invalid=validateAdmin(sid,idempotencyKey,request);if(invalid!=null)return ApiResult.fail(422,invalid);
        return once("QUERY_APPLIED",sid,idempotencyKey,request,()->requestAppliedOnce(sid.trim(),request));
    }

    private ApiResult<Map<String,Object>> requestAppliedOnce(String sid,JanusTakeoverAdminRequest request) {
        Map<String,Object> row=mapper.findForUpdate(sid);if(row==null)return ApiResult.fail(404,"K6_TAKEOVER_NOT_FOUND");
        long version=num(row,"rowVersion");if(request.expectedVersion()!=version)return ApiResult.fail(409,"K6_TAKEOVER_VERSION_CONFLICT");
        String id="reconcile-"+UUID.randomUUID();
        if(mapper.requestReconciliation(sid,version,id)!=1)return ApiResult.fail(409,"K6_TAKEOVER_VERSION_CONFLICT");
        Map<String,Object> after=mapper.find(sid);audit("K6_TAKEOVER_RECONCILIATION_REQUESTED",sid,request,after);
        Map<String,Object> result=new LinkedHashMap<>(after);result.put("fresh",false);return ApiResult.ok(result);
    }

    public ApiResult<Map<String,Object>> pending(long userId,String sid,String deviceId) {
        if(mapper.owns(userId,sid,deviceId)!=1)return ApiResult.fail(403,"JANUS_DEVICE_OWNERSHIP_MISMATCH");
        Map<String,Object> row=mapper.find(sid);if(row==null)return ApiResult.ok(Map.of("hasCommand",false));
        boolean reconciliation=StringUtils.hasText(text(row,"reconciliationId"))&&row.get("reconciledAt")==null;
        String phase=text(row,"phase");
        if(!reconciliation&&!Set.of("COMMAND_PENDING_ACK","REVOKE_PENDING_ACK").contains(phase))return ApiResult.ok(Map.of("hasCommand",false));
        Map<String,Object> out=new LinkedHashMap<>(row);out.put("hasCommand",true);
        if(reconciliation)out.put("commandType","QUERY_APPLIED");
        String commandType = reconciliation ? "QUERY_APPLIED" : text(row,"commandType");
        out.put("commandType", commandType);
        String target=text(row,"expectedTargetId");Integer version=intOrNull(row,"expectedTargetVersion");Long catalog=longOrNull(row,"expectedTargetCatalogVersion");
        if(Set.of("ACTIVATE","CHANGE_TARGET").contains(commandType)){
            JanusRemoteTargetView approved=targetRepository.find(target,version==null?0:version).orElse(null);
            if(approved==null||!"ACTIVE".equals(approved.status())||catalog==null||approved.catalogVersion()!=catalog)return ApiResult.fail(409,"JANUS_REMOTE_TARGET_UNAVAILABLE");
            out.put("remoteTargetUrl",approved.url());
        }
        return ApiResult.ok(out);
    }

    @Transactional
    public ApiResult<Map<String,Object>> progress(long userId,String sid,JanusTakeoverProgressRequest request) {
        if(request==null||!StringUtils.hasText(request.deviceId())||!StringUtils.hasText(request.commandId())||request.commandVersion()==null||request.commandVersion()<=0)return ApiResult.fail(422,"K6_TAKEOVER_PROGRESS_INVALID");
        if(mapper.owns(userId,sid,request.deviceId().trim())!=1)return ApiResult.fail(403,"JANUS_DEVICE_OWNERSHIP_MISMATCH");
        Map<String,Object> row=mapper.findForUpdate(sid);if(row==null)return ApiResult.fail(404,"K6_TAKEOVER_NOT_FOUND");
        if(!request.commandId().trim().equals(text(row,"commandId"))||request.commandVersion()!=num(row,"commandVersion"))return ApiResult.fail(409,"K6_TAKEOVER_STALE_COMMAND");
        if(StringUtils.hasText(request.reconciliationId())){
            boolean revoked="REVOKE".equals(text(row,"commandType"))&&"REVOKED".equals(text(row,"phase"));
            if(!validAppliedEvidence(row,request,revoked))return ApiResult.fail(422,"K6_RECONCILIATION_EVIDENCE_REQUIRED");
            if(mapper.reconcile(sid,request.reconciliationId().trim(),request.commandId().trim(),request.commandVersion(),request.actualTargetId().trim(),request.actualTargetVersion(),request.actualTargetCatalogVersion(),request.deviceAppliedVersion(),trim(request.deviceAppVersion()),trim(request.handoffReceipt()))!=1)return ApiResult.fail(409,"K6_RECONCILIATION_CONFLICT");
            return ApiResult.ok(mapper.find(sid));
        }
        String from=text(row,"phase");String to=upper(request.phase());
        if(!NEXT.getOrDefault(from,Set.of()).contains(to))return ApiResult.fail(409,"K6_TAKEOVER_ILLEGAL_PHASE_TRANSITION");
        String failureClass=trim(request.failureClass());
        if(Set.of("FAILED","REVOKE_FAILED").contains(to)&&(!FAILURE_CLASSES.contains(failureClass)||!StringUtils.hasText(request.failureMessage())))return ApiResult.fail(422,"K6_TAKEOVER_FAILURE_DETAIL_REQUIRED");
        if(!Set.of("FAILED","REVOKE_FAILED").contains(to)){failureClass=null;}
        if("SUCCEEDED".equals(to)&&!validAppliedEvidence(row,request,false))return ApiResult.fail(422,"K6_TAKEOVER_SUCCESS_EVIDENCE_REQUIRED");
        if("REVOKED".equals(to)&&!validAppliedEvidence(row,request,true))return ApiResult.fail(422,"K6_TAKEOVER_REVOKE_EVIDENCE_REQUIRED");
        if(mapper.progress(sid,num(row,"rowVersion"),request.commandId().trim(),request.commandVersion(),from,to,trim(request.actualTargetId()),request.actualTargetVersion(),request.actualTargetCatalogVersion(),request.deviceAppliedVersion(),trim(request.deviceAppVersion()),trim(request.handoffReceipt()),StringUtils.hasText(failureClass)?trim(request.failureCode()):null,failureClass,StringUtils.hasText(failureClass)?trim(request.failureMessage()):null)!=1)return ApiResult.fail(409,"K6_TAKEOVER_PROGRESS_CONFLICT");
        return ApiResult.ok(mapper.find(sid));
    }

    private boolean validAppliedEvidence(Map<String,Object> row,JanusTakeoverProgressRequest request,boolean revoked) {
        if(!StringUtils.hasText(request.actualTargetId())||request.actualTargetVersion()==null||request.actualTargetCatalogVersion()==null
                ||request.deviceAppliedVersion()==null||!StringUtils.hasText(request.deviceAppVersion())||!StringUtils.hasText(request.handoffReceipt()))return false;
        if(request.deviceAppliedVersion()!=num(row,"commandVersion"))return false;
        if(revoked)return "none".equals(request.actualTargetId().trim())&&request.actualTargetVersion()==0&&request.actualTargetCatalogVersion()==0;
        return request.actualTargetId().trim().equals(text(row,"expectedTargetId"))
                &&request.actualTargetVersion().equals(intOrNull(row,"expectedTargetVersion"))
                &&request.actualTargetCatalogVersion().equals(longOrNull(row,"expectedTargetCatalogVersion"));
    }

    private boolean validTarget(String key,Integer version,Long catalog){if(!StringUtils.hasText(key)||version==null||version<=0||catalog==null||catalog<=0)return false;JanusRemoteTargetView t=targetRepository.find(key.trim(),version).orElse(null);return t!=null&&"ACTIVE".equals(t.status())&&t.catalogVersion()==catalog;}
    private String validateAdmin(String sid,String idempotencyKey,JanusTakeoverAdminRequest request){if(!StringUtils.hasText(sid))return "SID_REQUIRED";if(!StringUtils.hasText(idempotencyKey)||idempotencyKey.length()>128)return "IDEMPOTENCY_KEY_INVALID";if(request==null||request.expectedVersion()==null||request.expectedVersion()<0)return "EXPECTED_VERSION_REQUIRED";if(!StringUtils.hasText(request.reason())||request.reason().trim().length()<8||request.reason().length()>500)return "REASON_REQUIRED";return null;}
    private void audit(String action,String sid,JanusTakeoverAdminRequest request,Map<String,Object> detail){audit.recordRequired(AuditLogWriteRequest.builder().action(action).resourceType("JANUS_TAKEOVER").resourceId(sid).actorUsername(AdminActorResolver.resolve(StringUtils.hasText(request.operator())?request.operator():actor())).riskLevel("HIGH").detail(Map.of("reason",request.reason().trim(),"after",detail)).build());}
    private String actor(){Authentication a=SecurityContextHolder.getContext().getAuthentication();return a==null?"unknown":a.getName();}
    private static String upper(String value){return trim(value)==null?"":trim(value).toUpperCase(Locale.ROOT);}
    private static String trim(String value){return StringUtils.hasText(value)?value.trim():null;}
    private static String text(Map<String,Object> row,String key){Object v=row.get(key);return v==null?"":String.valueOf(v).trim();}
    private static String textOrNull(Map<String,Object> row,String key){String v=text(row,key);return v.isEmpty()?null:v;}
    private static long num(Map<String,Object> row,String key){Object v=row.get(key);return v instanceof Number n?n.longValue():Long.parseLong(String.valueOf(v));}
    private static Long longOrNull(Map<String,Object> row,String key){Object v=row.get(key);return v==null?null:(v instanceof Number n?n.longValue():Long.valueOf(String.valueOf(v)));}
    private static Integer intOrNull(Map<String,Object> row,String key){Long v=longOrNull(row,key);return v==null?null:v.intValue();}

    @SuppressWarnings({"rawtypes","unchecked"})
    private ApiResult<Map<String,Object>> once(String action,String sid,String key,Object request,Supplier<ApiResult<Map<String,Object>>> supplier){
        return (ApiResult<Map<String,Object>>)(ApiResult)idempotency.execute("K6_TAKEOVER_"+action,key,hash(sid.trim()+"|"+request),ApiResult.class,(Supplier)supplier);
    }
    private static String hash(String value){
        try{return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)));}
        catch(NoSuchAlgorithmException ex){throw new IllegalStateException("K6_TAKEOVER_HASH_FAILED",ex);}
    }
}
