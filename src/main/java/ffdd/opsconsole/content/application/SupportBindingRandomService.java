package ffdd.opsconsole.content.application;

import com.fasterxml.jackson.databind.ObjectMapper;
import ffdd.opsconsole.common.boundary.ApplicationService;
import ffdd.opsconsole.content.domain.SupportRandom.*;
import ffdd.opsconsole.content.domain.SupportGroupFacts.*;
import ffdd.opsconsole.content.dto.SupportRandomRequest;
import ffdd.opsconsole.content.mapper.SupportBindingMapper;
import ffdd.opsconsole.content.mapper.SupportRandomMapper;
import ffdd.opsconsole.shared.api.ApiResult;
import ffdd.opsconsole.shared.audit.AuditLogService;
import ffdd.opsconsole.shared.audit.AuditLogWriteRequest;
import ffdd.opsconsole.shared.exception.BizException;
import ffdd.opsconsole.shared.idempotency.AdminIdempotencyService;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.LocalDateTime;
import java.util.*;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

@ApplicationService
@RequiredArgsConstructor
public class SupportBindingRandomService {
    private final SupportBindingMapper bindings;
    private final SupportRandomMapper mapper;
    private final SupportOwnershipService ownership;
    private final SupportBindingService service;
    private final AdminIdempotencyService idempotency;
    private final AuditLogService audit;
    private final ObjectMapper json;
    private final PlatformTransactionManager transactions;
    private final ProductionSupportPathGuard production;
    @Value("${nexion.support.binding.preview-ttl-seconds:300}") private long previewTtlSeconds;

    public Preview preview(SupportRandomRequest.Preview request) {
        production.requireOpsWriteAllowed();
        if(request==null || previewTtlSeconds<1 || previewTtlSeconds>3600)
            throw new BizException(422,"SUPPORT_RANDOM_PREVIEW_INVALID");
        boolean all=Boolean.TRUE.equals(request.allFiltered());
        if(all && request.customers()!=null && !request.customers().isEmpty()) throw new BizException(422,"SUPPORT_RANDOM_SELECTION_INVALID");
        ReadScope scope=service.managementScope(null,null);
        List<Customer> selected;
        if(all) selected=mapper.scopedPool(scope,blank(request.keyword()),blank(request.reason())).stream()
            .map(row->new Customer(((Number)row.get("id")).longValue(),((Number)row.get("poolVersion")).longValue())).toList();
        else selected=request.customers();
        if(selected==null || selected.isEmpty()) throw new BizException(422,"SUPPORT_RANDOM_SELECTION_REQUIRED");
        Set<Long> ids=new HashSet<>();
        for(Customer c:selected) if(c==null || !safeId(c.id()) || !safeId(c.poolVersion()) || !ids.add(c.id()))
            throw new BizException(422,"SUPPORT_RANDOM_SELECTION_INVALID");
        return shortTransaction().execute(status->previewLocked(selected,scope));
    }

    private Preview previewLocked(List<Customer> selected,ReadScope before) {
        service.prepareRandomLocks(selected,ownership.actorId());
        ReadScope scope=service.managementScope(before.requestedGroupId(),before.requestedAgentId());
        List<Customer> accepted=new ArrayList<>();List<FrozenCustomer> frozen=new ArrayList<>();List<Excluded> excluded=new ArrayList<>();
        for(Customer c:selected.stream().sorted(Comparator.comparing(Customer::id)).toList()) {
            ownership.requireManagingCustomer(c.id());
            String exclusion=exclusion(c);
            if(exclusion==null) {frozen.add(service.freezeRandomScope(c,scope));accepted.add(c);}
            else excluded.add(new Excluded(c.id(),exclusion));
        }
        var rules=bindings.rules();
        if(rules==null) throw new BizException(503,"SUPPORT_RULES_UNAVAILABLE");
        String id=UUID.randomUUID().toString();LocalDateTime expires=mapper.now().plusSeconds(previewTtlSeconds);
        mapper.insertPreview(id,ownership.actorId(),rules.version(),encode(frozen),encode(excluded),expires);
        return new Preview(id,ownership.actorId(),List.copyOf(accepted),accepted.size(),List.copyOf(excluded),rules.version(),expires);
    }

    @SuppressWarnings({"rawtypes","unchecked"})
    public ApiResult<Result> confirm(String key,SupportRandomRequest.Confirm request) {
        production.requireOpsWriteAllowed();
        if(request==null || request.previewId()==null || !request.previewId().matches("[a-fA-F0-9-]{36}") || !safeId(request.expectedRulesVersion()))
            throw new BizException(422,"SUPPORT_RANDOM_CONFIRM_INVALID");
        SupportBindingService.validateCommand(key,request.reason());
        Long actor=ownership.actorId();String digest=hash(encode(request));
        var recovery=idempotency.recoveryResult("SUPPORT_RANDOM:"+actor,key,digest,ApiResult.class);
        if(recovery.status()==AdminIdempotencyService.RecoveryStatus.MISMATCH) throw new BizException(409,"IDEMPOTENCY_KEY_PAYLOAD_MISMATCH");
        if(recovery.status()==AdminIdempotencyService.RecoveryStatus.SUCCEEDED) return authorizedResult((ApiResult<Result>)recovery.response());
        try {service.managementScope(null,null);}catch(BizException ex){abortKnown(actor,key,digest,ex);throw ex;}
        if(recovery.status()==AdminIdempotencyService.RecoveryStatus.UNKNOWN) {
            var operation=mapper.loadOperation(actor,key.trim());
            if(operation==null || !digest.equals(operation.get("requestHash"))) throw new BizException(409,"IDEMPOTENCY_RESULT_UNKNOWN");
            return authorizedResult(execute(key,request,actor,digest));
        }
        return authorizedResult((ApiResult<Result>)idempotency.executeRetained("SUPPORT_RANDOM:"+actor,key,digest,ApiResult.class,
            ()->execute(key,request,actor,digest)));
    }

    private ApiResult<Result> execute(String key,SupportRandomRequest.Confirm request,Long actor,String digest) {
        try {return executeOnce(key,request,actor,digest);}
        catch(BizException ex){abortKnown(actor,key,digest,ex);throw ex;}
    }
    private ApiResult<Result> executeOnce(String key,SupportRandomRequest.Confirm request,Long actor,String digest) {
        TransactionTemplate tx=shortTransaction();
        Map<String,Object> preview=tx.execute(status->{
            var found=mapper.loadPreview(request.previewId(),actor);
            if(found==null) throw new BizException(404,"SUPPORT_RANDOM_PREVIEW_NOT_FOUND");
            if(((Number)found.get("rulesVersion")).longValue()!=request.expectedRulesVersion()) throw new BizException(409,"SUPPORT_RULES_VERSION_CONFLICT");
            if(mapper.loadOperation(actor,key.trim())==null && !valid(found.get("valid"))) throw new BizException(409,"SUPPORT_RANDOM_PREVIEW_EXPIRED");
            decode(String.valueOf(found.get("customers"))); // Reject legacy previews before starting an operation.
            mapper.insertOperation(actor,key.trim(),request.previewId(),digest);
            var operation=mapper.loadOperation(actor,key.trim());
            if(!digest.equals(operation.get("requestHash"))) throw new BizException(409,"IDEMPOTENCY_KEY_PAYLOAD_MISMATCH");
            if("CONFLICT".equals(operation.get("status")))throw new BizException(409,"SUPPORT_RANDOM_OPERATION_CONFLICT");
            return found;
        });
        List<FrozenCustomer> customers=decode(String.valueOf(preview.get("customers")));
        List<Recipient> results=new ArrayList<>();
        for(FrozenCustomer frozen:customers) results.add(tx.execute(status-> {
                Customer customer=frozen.customer();
                ownership.lockCustomer(customer.id());
                var rules=bindings.rules();
                Recipient committed=mapper.loadResult(actor,key.trim(),customer.id());
                if(committed!=null) {ownership.customerQueryScope(customer.id());return committed;}
                service.prepareRandomLocks(List.of(customer),actor);
                service.managementScope(null,null);ownership.requireManagingCustomer(customer.id());
                String excluded=!valid(mapper.loadPreview(request.previewId(),actor).get("valid"))?"PREVIEW_EXPIRED"
                    :rules==null || !Objects.equals(rules.version(),request.expectedRulesVersion())?"RULES_VERSION_CHANGED":exclusion(customer);
                Recipient result;
                if(excluded!=null) {result=new Recipient(customer.id(),"CONFLICT",null,null,excluded);}
                else {
                    var assignment=service.randomUnbound(customer.id(),rules,key.trim(),String.valueOf(actor),request.reason().trim(),actor,frozen);
                    result=assignment==null?new Recipient(customer.id(),"NO_CANDIDATE",null,null,"NO_CANDIDATE")
                        :new Recipient(customer.id(),"ASSIGNED",assignment.id(),assignment.agentAdminId(),"ASSIGNED");
                }
                mapper.insertResult(actor,key.trim(),request.previewId(),result);
                audit.recordRequired(AuditLogWriteRequest.builder().action("SUPPORT_RANDOM_ASSIGNED").resourceType("SUPPORT_ASSIGNMENT")
                    .resourceId(String.valueOf(customer.id())).bizNo(String.valueOf(customer.id())).actorType("ADMIN")
                    .actorUsername(String.valueOf(actor)).result("SUCCESS").riskLevel("HIGH")
                    .detail(Map.of("result",result,"commandKey",key.trim(),"reason",request.reason().trim())).build());
                return result;
        }));
        tx.executeWithoutResult(status->mapper.complete(actor,key.trim()));
        return ApiResult.ok(new Result(key.trim(),request.previewId(),List.copyOf(results)));
    }

    /** Only a known rolled-back business rejection terminates an owned intent; SQL/transport failures remain UNKNOWN. */
    private void abortKnown(Long actor,String key,String digest,BizException failure) {
        if(!Set.of(403,404,409).contains(failure.getCode()))return;
        try {
            shortTransaction().executeWithoutResult(status->{
                if(mapper.abortKnownConflict(actor,key.trim(),digest)==1)
                    audit.recordRequired(AuditLogWriteRequest.builder().action("SUPPORT_RANDOM_ABORTED")
                        .resourceType("SUPPORT_ASSIGNMENT").resourceId(key.trim()).actorType("ADMIN")
                        .actorUsername(String.valueOf(actor)).result("FAILED").riskLevel("HIGH")
                        .detail(Map.of("operationId",key.trim(),"outcome",failure.getMessage())).build());
            });
        } catch(RuntimeException closureFailure) {failure.addSuppressed(closureFailure);}
    }

    /** Read committed per-customer receipts even when the outer command lease became UNKNOWN. */
    @Transactional
    public Map<String,Object> recover(String key) {
        Long actor=ownership.actorId();
        var operation=mapper.loadOperation(actor,key.trim());
        if(operation==null) return Map.of("status","UNKNOWN");
        var results=mapper.results(actor,key.trim());
        results.forEach(result->ownership.customerQueryScope(result.customerId()));
        return Map.of("status",operation.get("status"),"operationId",key.trim(),"previewId",operation.get("previewId"),"customers",results);
    }

    private TransactionTemplate shortTransaction() {
        var tx=new TransactionTemplate(transactions);
        tx.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        tx.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);
        return tx;
    }

    private String exclusion(Customer customer) {
        if(bindings.canonicalCustomer(customer.id())!=1) return "ISOLATED_CUSTOMER";
        if(bindings.current(customer.id())!=null) return "ALREADY_BOUND";
        if(!Objects.equals(bindings.poolVersion(customer.id()),customer.poolVersion())) return "POOL_VERSION_CHANGED";
        return "MIGRATION_REVIEW".equals(bindings.poolReason(customer.id()))?"REVIEW_REQUIRED":null;
    }
    private String encode(Object value) {try{return json.writeValueAsString(value);}catch(Exception ex){throw new IllegalStateException("Random assignment serialization failed",ex);}}
    private List<FrozenCustomer> decode(String value) {
        try {
            var tree=json.readTree(value);
            if(!tree.isArray())throw new BizException(409,"SUPPORT_RANDOM_PREVIEW_SCOPE_REQUIRED");
            for(var node:tree) if(!node.hasNonNull("customer") || !node.hasNonNull("routeId") || !node.hasNonNull("groupId")
                    || !node.hasNonNull("routeVersion") || !node.hasNonNull("groupVersion") || !node.hasNonNull("ownerAdminId")
                    || !node.hasNonNull("ownerHistoryId") || !node.hasNonNull("ownerVersion")
                    || !node.hasNonNull("candidates"))throw new BizException(409,"SUPPORT_RANDOM_PREVIEW_SCOPE_REQUIRED");
            return json.readValue(value,json.getTypeFactory().constructCollectionType(List.class,FrozenCustomer.class));
        } catch(BizException ex){throw ex;}catch(Exception ex){throw new IllegalStateException("Random preview is invalid",ex);}
    }
    private ApiResult<Result> authorizedResult(ApiResult<Result> result) {
        if(result==null)throw new BizException(409,"IDEMPOTENCY_RESULT_UNKNOWN");
        shortTransaction().executeWithoutResult(status->{
            var customers=json.valueToTree(result).path("data").path("customers");
            if(result.getCode()==0 && !customers.isArray())throw new BizException(409,"IDEMPOTENCY_RESULT_UNKNOWN");
            for(var recipient:customers) {
                var id=recipient.path("customerId");
                if(!id.isIntegralNumber() || !id.canConvertToLong() || !safeId(id.longValue()))throw new BizException(409,"IDEMPOTENCY_RESULT_UNKNOWN");
                ownership.customerQueryScope(id.longValue());
            }
        });
        return result;
    }
    private static boolean valid(Object value){return Boolean.TRUE.equals(value) || value instanceof Number n && n.intValue()==1;}
    private static boolean safeId(Long id){return id!=null && id>0 && id<=9007199254740991L;}
    private static String blank(String s){return s==null || s.isBlank()?null:s.trim();}
    private static String hash(String value){try{return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)));}catch(java.security.NoSuchAlgorithmException ex){throw new IllegalStateException(ex);}}
}
