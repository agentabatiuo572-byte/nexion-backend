package ffdd.opsconsole.onboarding.facade;

import ffdd.opsconsole.common.boundary.PhoneReplacementContract;
import ffdd.opsconsole.onboarding.application.PhoneReplacementService;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Safe public read projection; HTTP adapters still enforce current identity, authority and data scope. */
@Service
@RequiredArgsConstructor
public class PhoneReplacementFacade implements PhoneReplacementContract {
    private final PhoneReplacementService service;
    public Policy currentPolicy() { return service.currentPolicy(); }
    public Policy lockCurrentPolicy() { return service.lockCurrentPolicy(); }
    public Map<String,String> validatePolicy(Policy candidate,long now) { return service.validatePolicy(candidate,now); }
    public Operation adminOperationContext(String id) { return service.adminOperationContext(id); }
    public Operation adminOperationContextByKey(long actor,String type,String key,String environment,String run) { return service.adminOperationContextByKey(actor,type,key,environment,run); }
    @Transactional
    public Receipt createRequest(Account account,String installation,long version,String reason,String key,Runnable currentAppCheck) {
        var locked=service.lockAccount(service.lockPolicy(),account,Objects.requireNonNull(currentAppCheck));
        var command=service.openOperation(locked,new Actor("USER",account.userId()),"PHONE_REQUEST_CREATE",key,
                Map.of("installationId",installation,"expectedBindingVersion",version,"reason",reason));
        return service.createRequest(locked,installation,version,reason,command);
    }
    @Transactional
    public Receipt linkSupport(Account account,String requestId,String ref,long supportAccount,String key,Runnable currentAppCheck) {
        var locked=service.lockAccount(service.lockPolicy(),account,Objects.requireNonNull(currentAppCheck));
        var command=service.openOperation(locked,new Actor("USER",account.userId()),"PHONE_REQUEST_SUPPORT_LINK",key,
                Map.of("requestId",requestId,"supportRef",ref));
        return service.linkSupport(locked,requestId,ref,supportAccount,command);
    }
    @Transactional
    public Receipt issueGrant(Account account,long adminId,IssueInput input,VerifiedEvidence evidence,String key) {
        var locked=service.lockAdminAccount(service.lockPolicy(),account);
        // Stable source reference participates in identity; a freshly read evidence snapshot does not change a replay.
        var command=service.openOperation(locked,new Actor("ADMIN",adminId),"PHONE_GRANT_ISSUE",key,
                Map.of("input",input,"evidenceRef",evidence.reference()));
        return service.issueGrant(locked,input,evidence,command);
    }
    @Transactional
    public Receipt closeRequest(Account account,long adminId,String requestId,String reason,String key) {
        var locked=service.lockAdminAccount(service.lockPolicy(),account);
        var command=service.openOperation(locked,new Actor("ADMIN",adminId),"PHONE_REQUEST_CLOSE",key,
                Map.of("requestId",requestId,"reason",reason));
        return service.closeRequest(locked,requestId,reason,command);
    }
    @Transactional
    public Receipt revokeGrant(Account account,long adminId,String grantId,String reason,String key) {
        var locked=service.lockAdminAccount(service.lockPolicy(),account);
        var command=service.openOperation(locked,new Actor("ADMIN",adminId),"PHONE_GRANT_REVOKE",key,
                Map.of("grantId",grantId,"reason",reason));
        return service.revokeGrant(locked,grantId,reason,command);
    }
    public Eligibility eligibility(Account account,String installation,String hardware,boolean currentApp,List<String> blocks) {
        return service.eligibility(account,installation,hardware,currentApp,blocks);
    }
    public Receipt operation(Account account,String operationId) { return service.operation(account,operationId); }
    public Receipt userOperation(Account account,String operationId) { return service.userOperation(account,operationId); }
    public Receipt operationByKey(Account account,Actor actor,String type,String key) { return service.operationByKey(account,actor,type,key); }
    public CurrentRequest currentRequest(Account account,String installation) { return service.currentRequest(account,installation); }
    public List<Request> requests(Account account,int offset,int limit) { return service.requests(account,offset,limit); }
    public List<Map<String,Object>> replacementEvents(Account account,int offset,int limit) { return service.replacementEvents(account,offset,limit); }
}
