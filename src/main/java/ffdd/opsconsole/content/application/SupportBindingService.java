package ffdd.opsconsole.content.application;

import ffdd.opsconsole.common.boundary.ApplicationService;
import ffdd.opsconsole.content.domain.SupportAssignment;
import ffdd.opsconsole.content.domain.SupportRules;
import ffdd.opsconsole.content.dto.SupportBindingRequest;
import ffdd.opsconsole.content.dto.SupportRulesRequest;
import ffdd.opsconsole.content.mapper.SupportBindingMapper;
import ffdd.opsconsole.shared.api.ApiResult;
import ffdd.opsconsole.shared.audit.AuditLogService;
import ffdd.opsconsole.shared.audit.AuditLogWriteRequest;
import ffdd.opsconsole.shared.exception.BizException;
import ffdd.opsconsole.shared.idempotency.AdminIdempotencyService;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.*;
import lombok.RequiredArgsConstructor;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.transaction.annotation.Transactional;

@ApplicationService
@RequiredArgsConstructor
public class SupportBindingService {
    private final SupportBindingMapper mapper;
    private final SupportOwnershipService ownership;
    private final AdminIdempotencyService idempotency;
    private final AuditLogService audit;
    private final ApplicationEventPublisher events;
    private final ffdd.opsconsole.content.domain.SupportAgentRepository agents;
    private final SupportTicketOwnerService ticketOwners;

    public SupportRules rules() { ownership.requireSupervisor(); return mapper.rules(); }

    @Transactional(readOnly=true)
    public ffdd.opsconsole.shared.api.PageResult<Map<String,Object>> pool(String keyword, String reason, long page, int size) {
        ownership.requireSupervisor();
        if(page<1 || size<1 || size>100 || page>Long.MAX_VALUE/size) throw new BizException(422,"SUPPORT_PAGE_INVALID");
        return new ffdd.opsconsole.shared.api.PageResult<>(mapper.poolCount(blank(reason),blank(keyword)),page,size,mapper.pool(blank(reason),blank(keyword),(page-1)*size,size));
    }

    @Transactional(readOnly=true)
    public ffdd.opsconsole.shared.api.PageResult<Map<String,Object>> handover(Long agent,boolean unavailable,long page,int size) {
        ownership.requireSupervisor();
        if(page<1 || size<1 || size>100 || page>Long.MAX_VALUE/size) throw new BizException(422,"SUPPORT_PAGE_INVALID");
        return new ffdd.opsconsole.shared.api.PageResult<>(mapper.handoverCount(agent,unavailable),page,size,mapper.handover(agent,unavailable,(page-1)*size,size));
    }

    public ApiResult<SupportRules> updateRules(String key, SupportRulesRequest r) {
        ownership.requireSuperAdmin();
        if (r == null) throw new BizException(422,"SUPPORT_RULES_REQUIRED");
        validateCommand(key,r.reason());
        if (r.expectedVersion() == null || r.expectedVersion() < 1
                || !Set.of("UNCONFIGURED","LIMITED","UNLIMITED").contains(String.valueOf(r.inheritanceMode()))
                || ("LIMITED".equals(r.inheritanceMode()) ? r.maxInheritanceDepth()==null || r.maxInheritanceDepth()<0 : r.maxInheritanceDepth()!=null)
                || invalidDays(r.dormantDays()) || invalidDays(r.maintenanceDays()) || invalidDays(r.activityWindowDays())
                || (r.dormantDays()!=null && r.activityWindowDays()!=null && r.activityWindowDays()>r.dormantDays()))
            throw new BizException(422,"SUPPORT_RULES_INVALID");
        return command("RULES",key,r,()-> {
            ownership.requireSuperAdmin();
            if(mapper.updateRules(r.dormantDays(),r.maintenanceDays(),r.activityWindowDays(),r.inheritanceMode(),r.maxInheritanceDepth(),r.expectedVersion(),ownership.actorId(),r.reason().trim())!=1)
                throw new BizException(409,"SUPPORT_RULES_VERSION_CONFLICT");
            record("SUPPORT_RULES_CHANGED","1",key,r.reason(),Map.of("request",r));
            return ApiResult.ok(mapper.rules());
        });
    }

    public ApiResult<List<SupportAssignment>> transfer(String key, SupportBindingRequest r) {
        validateTransfer(key,r);
        return command("TRANSFER",key,r,()->transferOnce(key,r));
    }

    public ApiResult<List<ffdd.opsconsole.content.domain.SupportAgentAssignmentView>> transferLegacy(String key,SupportBindingRequest r) {
        validateTransfer(key,r);
        return command("LEGACY_TRANSFER",key,r,()-> {
            transferOnce(key,r);
            Set<Long> ids=r.customers().stream().map(SupportBindingRequest.Customer::id).collect(java.util.stream.Collectors.toSet());
            return ApiResult.ok(agents.listActiveAssignments(List.of(r.targetAgentAdminId())).stream().filter(a->ids.contains(a.userId())).toList());
        });
    }

    public ApiResult<ffdd.opsconsole.content.domain.SupportAgentAssignmentView> transferLegacySingle(String key,SupportBindingRequest r) {
        validateTransfer(key,r);
        if(r.customers().size()!=1)throw new BizException(422,"SUPPORT_BINDING_REQUEST_INVALID");
        return command("LEGACY_SINGLE",key,r,()-> {
            transferOnce(key,r);
            return ApiResult.ok(agents.listActiveAssignments(List.of(r.targetAgentAdminId())).stream()
                .filter(a->a.userId().equals(r.customers().get(0).id())).findFirst().orElseThrow());
        });
    }

    @Transactional(propagation=org.springframework.transaction.annotation.Propagation.MANDATORY)
    public ApiResult<List<SupportAssignment>> transferInTransaction(String key,SupportBindingRequest r) {
        validateTransfer(key,r);
        return transferOnce(key,r);
    }

    private void validateTransfer(String key,SupportBindingRequest r) {
        ownership.requireSupervisor();
        if(r==null || r.targetAgentAdminId()==null || r.targetAgentAdminId()<1 || r.customers()==null || r.customers().isEmpty() || r.customers().size()>100)
            throw new BizException(422,"SUPPORT_BINDING_REQUEST_INVALID");
        validateCommand(key,r.reason());
        Set<Long> ids=new HashSet<>();
        for(var c:r.customers()) if(c==null || c.id()==null || c.id()<1 || !ids.add(c.id()) || c.expectedVersion()==null || c.expectedVersion()<1
                || (c.expectedAssignmentId()!=null && c.expectedAssignmentId()<1)) throw new BizException(422,"SUPPORT_BINDING_EXPECTATION_REQUIRED");
    }

    private ApiResult<List<SupportAssignment>> transferOnce(String key, SupportBindingRequest r) {
        ownership.requireSupervisor();
        List<SupportBindingRequest.Customer> customers=r.customers().stream().sorted(Comparator.comparing(SupportBindingRequest.Customer::id)).toList();
        customers.forEach(c->ownership.lockCustomer(c.id()));
        if(mapper.lockAgent(r.targetAgentAdminId())==null || mapper.eligibleAgent(r.targetAgentAdminId())!=1)
            throw new BizException(422,"SUPPORT_AGENT_UNAVAILABLE");
        Map<Long,SupportAssignment> previous=new HashMap<>();
        for(var c:customers) {
            var old=mapper.current(c.id());
            Long version=old==null?mapper.poolVersion(c.id()):old.version();
            if(!Objects.equals(c.expectedAssignmentId(),old==null?null:old.id()) || !Objects.equals(c.expectedVersion(),version))
                throw new BizException(409,"SUPPORT_BINDING_VERSION_CONFLICT");
            previous.put(c.id(),old);
        }
        List<SupportAssignment> result=new ArrayList<>();
        for(var c:customers) {
            var old=previous.get(c.id());
            if(old!=null && old.agentAdminId().equals(r.targetAgentAdminId())) {result.add(old);continue;}
            if(old!=null && mapper.endAssignment(old.id(),old.version())!=1) throw new BizException(409,"SUPPORT_BINDING_VERSION_CONFLICT");
            mapper.insertAssignment(r.targetAgentAdminId(),c.id(),String.valueOf(ownership.actorId()),r.reason().trim(),"MANUAL",c.id(),0,null,null,key.trim());
            mapper.leavePool(c.id());
            var current=mapper.current(c.id());
            ticketOwners.synchronizeCurrentOwner(c.id());
            // S4 must handle this synchronous event in the same transaction (never AFTER_COMMIT).
            // Exceptions roll back assignment/history/audit together; no maintenance completion is claimed by S3.
            events.publishEvent(new SupportAssignmentChanged(c.id(),old,current));
            record("SUPPORT_ASSIGNMENT_CHANGED",String.valueOf(c.id()),key,r.reason(),Map.of("before",old==null?"UNBOUND":old,"after",current));
            result.add(current);
        }
        return ApiResult.ok(result);
    }

    /** Caller is the registration transaction, already holding the direct inviter's nx_user lock. */
    @Transactional(propagation=org.springframework.transaction.annotation.Propagation.MANDATORY)
    public void register(Long customer, Long inviter) {
        ownership.lockCustomer(customer);
        if(mapper.current(customer)!=null || mapper.poolVersion(customer)!=null) throw new BizException(409,"SUPPORT_REGISTRATION_ALREADY_BOUND");
        if(inviter!=null) ownership.lockCustomer(inviter);
        SupportRules rules=mapper.rules();
        String reason;
        SupportAssignment parent=inviter==null?null:mapper.current(inviter);
        if(rules==null || "UNCONFIGURED".equals(rules.inheritanceMode())) reason="RULE_UNCONFIGURED";
        else if(inviter==null) reason="NO_INVITER";
        else if(inviter.equals(customer)) reason="MIGRATION_REVIEW";
        else if(parent==null) reason="INVITER_UNBOUND";
        else if(parent.depth()==null || parent.depth()<0 || parent.depth()==Integer.MAX_VALUE || parent.segmentRootId()==null || parent.segmentRootId().equals(customer)) reason="MIGRATION_REVIEW";
        else if(mapper.lockAgent(parent.agentAdminId())==null || mapper.eligibleAgent(parent.agentAdminId())!=1) reason="AGENT_UNAVAILABLE";
        else if("LIMITED".equals(rules.inheritanceMode()) && parent.depth()+1>rules.maxInheritanceDepth()) reason="DEPTH_LIMIT";
        else {
            mapper.insertAssignment(parent.agentAdminId(),customer,"registration","Registration invitation inheritance","INHERITED",parent.segmentRootId(),parent.depth()+1,parent.id(),rules.version(),"registration:"+customer);
            ticketOwners.synchronizeCurrentOwner(customer);
            return;
        }
        mapper.enterPool(customer,reason);
    }

    public record SupportAssignmentChanged(Long customerId, SupportAssignment previous, SupportAssignment current) {}

    private void record(String action,String id,String key,String reason,Map<String,Object> detail) {
        Map<String,Object> values=new LinkedHashMap<>(detail);values.put("reason",reason.trim());values.put("commandKey",key.trim());
        audit.recordRequired(AuditLogWriteRequest.builder().action(action).resourceType("SUPPORT_ASSIGNMENT").resourceId(id).bizNo(id)
                .actorType("ADMIN").actorUsername(String.valueOf(ownership.actorId())).result("SUCCESS").riskLevel("HIGH").detail(values).build());
    }
    @SuppressWarnings({"rawtypes","unchecked"})
    private <T> ApiResult<T> command(String op,String key,Object request,java.util.function.Supplier<ApiResult<T>> action) {
        return (ApiResult<T>)idempotency.executeRetained("SUPPORT_"+op+":"+ownership.actorId(),key,hash(request),ApiResult.class,(java.util.function.Supplier)action);
    }
    private static String hash(Object r) {
        try {return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(String.valueOf(r).getBytes(StandardCharsets.UTF_8)));}
        catch(java.security.NoSuchAlgorithmException e){throw new IllegalStateException(e);}
    }
    public static void validateCommand(String key,String reason) {
        if(key==null || key.trim().length()<8 || key.trim().length()>128) throw new BizException(422,"IDEMPOTENCY_KEY_INVALID");
        if(reason==null || reason.trim().length()<8 || reason.trim().length()>200) throw new BizException(422,"REASON_LENGTH_INVALID");
    }
    private static boolean invalidDays(Integer d) {return d!=null && d<=0;}
    private static String blank(String s) {return s==null || s.isBlank()?null:s.trim();}
}
