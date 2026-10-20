package ffdd.opsconsole.user.application;

import com.fasterxml.jackson.databind.ObjectMapper;
import ffdd.opsconsole.common.boundary.*;
import ffdd.opsconsole.common.boundary.PhoneReplacementContract.*;
import ffdd.opsconsole.shared.audit.AuditLogService;
import ffdd.opsconsole.shared.audit.AuditLogWriteRequest;
import ffdd.opsconsole.shared.exception.PhoneReplacementException;
import ffdd.opsconsole.shared.security.PhoneReplacementAdminAccess;
import ffdd.opsconsole.user.dto.PhoneReplacementCommands.*;
import ffdd.opsconsole.user.mapper.PhoneReplacementVerificationMapper;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Clock;
import java.time.LocalDateTime;
import java.util.*;
import lombok.RequiredArgsConstructor;
import org.springframework.transaction.annotation.Transactional;

@ApplicationService
@RequiredArgsConstructor
public class PhoneReplacementOperationsService {
    public static final String READ="user_c5_read";
    public static final String GRANT="user_c5_phone_replacement_exception_grant";
    public static final String REVOKE="user_c5_phone_replacement_exception_revoke";
    private final PhoneReplacementAdminAccess access;
    private final OpsUserService users;
    private final PhoneReplacementContract domain;
    private final PhoneReplacementAdminReadContract views;
    private final PhoneReplacementPolicyOperations policies;
    private final PhoneReplacementSupportContract support;
    private final PhoneReplacementVerificationMapper verifications;
    private final AuditLogService audit;
    private final Clock clock;

    public record Slice<T>(List<T> items,int pageNum,int pageSize,boolean hasMore) { }
    public record VerificationView(String evidenceRef,String requestId,String supportRef,long verifiedBy,long verifiedAt) { }

    @Transactional
    public Map<String,Object> binding(long userId) {
        access.require(READ);
        var account=account(userId);
        return views.overview(account);
    }

    @Transactional(readOnly = true)
    public Slice<Request> requests(long userId,int page,int size) {
        access.require(READ);var account=account(userId);int offset=offset(page,size);
        return slice(domain.requests(account,offset,size+1),page,size);
    }

    @Transactional(readOnly = true)
    public Slice<?> history(long userId,String kind,int page,int size) {
        access.require(READ);var account=account(userId);int offset=offset(page,size);
        return switch(kind==null?"SUCCESS":kind) {
            case "SUCCESS" -> slice(domain.replacementEvents(account,offset,size+1),page,size);
            case "REQUEST" -> slice(views.requestHistory(account,offset,size+1),page,size);
            case "GRANT" -> slice(views.grants(account,offset,size+1),page,size);
            default -> throw new PhoneReplacementException(422,"PHONE_HISTORY_KIND_INVALID");
        };
    }

    @Transactional(readOnly = true)
    public VerificationView verificationByKey(long userId,String key) {
        long actor=access.require(GRANT);var account=account(userId);
        var row=verifications.byKey(actor,key(key));
        if(row==null || row.userId()!=userId || row.verifiedBy()!=actor
                || !row.sourceEnvironment().equals(account.sourceEnvironment()) || !row.runId().equals(account.runId()))return null;
        return verifiedView(row);
    }

    /** An explicit human attestation of this request's existing support record. No inferred verification. */
    @Transactional(rollbackFor=Exception.class)
    public VerificationView verify(long userId,String requestId,Verify input,String key) {
        long actor=access.require(GRANT);var account=account(userId);
        if(input==null || requestId==null || requestId.isBlank() || requestId.length()>64)
            throw new PhoneReplacementException(422,"PHONE_REPLACEMENT_CONFIRMATION_REQUIRED");
        confirm(input.confirmed(),input.reason());key=key(key);
        String hash=hash(List.of(userId,account.sourceEnvironment(),account.runId(),requestId,input.reason().trim()));
        var prior=verifications.byKey(actor,key);
        if(prior!=null){if(!hash.equals(prior.requestHash()))throw new PhoneReplacementException(409,"IDEMPOTENCY_MISMATCH");return verifiedView(prior);}
        var request=views.request(account,requestId);
        if(request==null || !"PENDING".equals(request.status()))throw new PhoneReplacementException(409,"REQUEST_UNAVAILABLE");
        if(!"LINKED".equals(request.supportLinkStatus()) || request.supportRef()==null)
            throw new PhoneReplacementException(422,"PHONE_REPLACEMENT_EVIDENCE_REQUIRED");
        support.requireConversationOwner(userId,request.supportRef());
        var row=new PhoneReplacementVerificationMapper.Row("PRV"+UUID.randomUUID().toString().replace("-",""),userId,
                account.sourceEnvironment(),account.runId(),requestId,request.targetInstallationId(),request.bindingVersion(),
                request.supportRef(),actor,clock.millis(),input.reason().trim(),key,hash);
        if(verifications.insert(row)!=1) {
            var concurrent=verifications.currentByKey(actor,key);
            if(concurrent==null)throw new PhoneReplacementException(409,"PHONE_VERIFICATION_WRITE_CONFLICT");
            if(!hash.equals(concurrent.requestHash()))throw new PhoneReplacementException(409,"IDEMPOTENCY_MISMATCH");
            return verifiedView(concurrent);
        }
        audit.recordRequired(AuditLogWriteRequest.builder().traceId(row.evidenceRef()).actorType("ADMIN").actorId(actor)
                .action("ADMIN.PHONE_REPLACEMENT_REQUEST_VERIFIED").resourceType("PHONE_REPLACEMENT_REQUEST").resourceId(requestId)
                .result("SUCCEEDED").riskLevel("HIGH").detail(Map.of("evidenceRef",row.evidenceRef(),"requestId",requestId,
                        "supportRef",request.supportRef(),"reason",input.reason().trim())).build());
        return verifiedView(row);
    }

    @Transactional(rollbackFor=Exception.class)
    public Receipt issue(long userId,Issue input,String key) {
        long actor=access.require(GRANT);var account=account(userId);
        if(input==null)throw new PhoneReplacementException(422,"PHONE_REPLACEMENT_COMMAND_INVALID");
        confirm(input.confirmed(),input.reason());key=key(key);
        if(input.requestId()==null || input.expectedBindingVersion()==null || input.waiveCount()==null || input.waiveInterval()==null
                || input.evidenceRef()==null || !input.waiveCount() && !input.waiveInterval())
            throw new PhoneReplacementException(422,"PHONE_REPLACEMENT_COMMAND_INVALID");
        var evidence=verifications.find(input.evidenceRef());
        if(evidence==null || evidence.userId()!=userId || evidence.verifiedBy()!=actor
                || !evidence.sourceEnvironment().equals(account.sourceEnvironment()) || !evidence.runId().equals(account.runId())
                || !evidence.requestId().equals(input.requestId()))throw new PhoneReplacementException(422,"PHONE_REPLACEMENT_EVIDENCE_REQUIRED");
        // Immutable evidence remains usable to identify an original command after expiry or consumption.
        // The domain recovers the command before validating current binding/request/grant state.
        var verified=new VerifiedEvidence(evidence.evidenceRef(),userId,actor,true,Map.of(
                "requestId",evidence.requestId(),"supportRef",evidence.supportRef(),"targetInstallationId",evidence.targetInstallationId(),
                "bindingVersion",evidence.bindingVersion(),"verifiedAt",evidence.verifiedAt()));
        return domain.issueGrant(account,actor,new IssueInput(input.requestId(),input.expectedBindingVersion(),
                input.waiveCount(),input.waiveInterval(),input.reason().trim()),verified,key);
    }

    @Transactional(rollbackFor=Exception.class)
    public Receipt close(long userId,String requestId,Confirm input,String key) {
        long actor=access.require(GRANT);var account=account(userId);
        if(input==null)throw new PhoneReplacementException(422,"PHONE_REPLACEMENT_CONFIRMATION_REQUIRED");
        confirm(input.confirmed(),input.reason());
        return domain.closeRequest(account,actor,requestId,input.reason().trim(),key(key));
    }

    @Transactional(rollbackFor=Exception.class)
    public Receipt revoke(long userId,String grantId,Confirm input,String key) {
        long actor=access.require(REVOKE);var account=account(userId);
        if(input==null)throw new PhoneReplacementException(422,"PHONE_REPLACEMENT_CONFIRMATION_REQUIRED");
        confirm(input.confirmed(),input.reason());
        return domain.revokeGrant(account,actor,grantId,input.reason().trim(),key(key));
    }

    @Transactional(readOnly = true)
    public Receipt operation(String id) {
        if(id==null || id.length()>64)throw new PhoneReplacementException(422,"PHONE_OPERATION_ID_INVALID");
        if(id.startsWith("PPO"))return policies.operation(id);
        access.require(GRANT,REVOKE);
        var context=domain.adminOperationContext(id);
        return recover(context);
    }

    @Transactional(readOnly = true)
    public Receipt operationByKey(String type,String key) {
        key=key(key);
        if("PHONE_POLICY_PUBLISH".equals(type) || "PHONE_POLICY_PARAM_PUBLISH".equals(type))return policies.operationByKey(type,key);
        long actor=access.require(permission(type));
        var scope=views.scope();
        return recover(domain.adminOperationContextByKey(actor,type,key,scope.sourceEnvironment(),scope.runId()));
    }

    private Receipt recover(Operation context) {
        if(context==null)return null; // Unknown remains unknown, never a failed/new command.
        access.require(permission(context.commandType()));
        var account=account(context.userId());
        if(!account.sourceEnvironment().equals(context.sourceEnvironment()) || !account.runId().equals(context.runId()))
            throw new PhoneReplacementException(404,"PHONE_OPERATION_UNAVAILABLE");
        return domain.operation(account,context.operationId());
    }
    private Account account(long userId) {
        // Reuse the exact C-domain support/customer data-scope gate for reads, writes and recovery.
        var profile=users.profile(userId);
        if(profile.getCode()!=0 || profile.getData()==null)throw new PhoneReplacementException(profile.getCode()==0?404:profile.getCode(),profile.getMessage());
        return views.account(userId);
    }
    private static String permission(String type) {
        if("PHONE_GRANT_ISSUE".equals(type) || "PHONE_REQUEST_CLOSE".equals(type))return GRANT;
        if("PHONE_GRANT_REVOKE".equals(type))return REVOKE;
        throw new PhoneReplacementException(422,"PHONE_COMMAND_TYPE_INVALID");
    }
    private static void confirm(Boolean confirmed,String reason) {
        if(!Boolean.TRUE.equals(confirmed))throw new PhoneReplacementException(422,"PHONE_REPLACEMENT_CONFIRMATION_REQUIRED");
        if(reason==null || reason.trim().length()<8 || reason.trim().length()>200)throw new PhoneReplacementException(422,"PHONE_REPLACEMENT_REASON_INVALID");
    }
    private static String key(String key) {
        if(key==null || key.isBlank() || key.length()>128)throw new PhoneReplacementException(422,"IDEMPOTENCY_KEY_REQUIRED");
        return key.trim();
    }
    private static int offset(int page,int size) {
        if(page<1 || page>1000000 || size<1 || size>100)throw new PhoneReplacementException(422,"PAGE_INVALID");
        return (page-1)*size;
    }
    private static <T> Slice<T> slice(List<T> rows,int page,int size) {
        return new Slice<>(List.copyOf(rows.subList(0,Math.min(rows.size(),size))),page,size,rows.size()>size);
    }
    private static VerificationView verifiedView(PhoneReplacementVerificationMapper.Row row) {
        return new VerificationView(row.evidenceRef(),row.requestId(),row.supportRef(),row.verifiedBy(),row.verifiedAt());
    }
    private static String hash(Object input) {
        try{return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(new ObjectMapper().writeValueAsString(input).getBytes(StandardCharsets.UTF_8)));}
        catch(Exception error){throw new IllegalStateException("PHONE_VERIFICATION_HASH_FAILED",error);}
    }
}
