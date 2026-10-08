package ffdd.opsconsole.content.application;

import ffdd.opsconsole.common.boundary.ApplicationService;
import ffdd.opsconsole.content.domain.SupportAssignment;
import ffdd.opsconsole.content.domain.SupportGroupFacts.*;
import ffdd.opsconsole.content.mapper.SupportBindingMapper;
import ffdd.opsconsole.content.mapper.SupportGroupMapper;
import ffdd.opsconsole.shared.exception.BizException;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.annotation.Transactional;

/** Shared object authorization for HTTP, replay, sockets, streams and private attachments. */
@ApplicationService
@RequiredArgsConstructor
public class SupportOwnershipService {
    private final SupportBindingMapper mapper;
    private final SupportGroupMapper groups;

    /** Support customer reads narrow this domain only; other domains retain their existing RBAC. */
    @Transactional
    public boolean currentSupportReader() {
        List<String> roles=mapper.roles(actorId());
        if(roles.isEmpty()) throw new BizException(403,"SUPPORT_ACCOUNT_UNAVAILABLE");
        return roles.stream().anyMatch("SUPPORT"::equalsIgnoreCase)
                && roles.stream().noneMatch(SupportOwnershipService::superRole);
    }

    /** Collection mode never grants access by itself; each SQL statement rechecks current facts. */
    @Transactional
    public ReadScope queryScope(ReadMode mode, Long groupId, Long agentId) {
        Long actor=actorId();
        ReadScope scope;
        try { scope=new ReadScope(actor,mode,groupId,agentId); }
        catch (IllegalArgumentException ex) { throw new BizException(422,"SUPPORT_READ_SCOPE_INVALID"); }
        boolean allowed=switch(mode) {
            case PERSONAL -> groups.qualificationCurrent(actor,"SERVICE")!=null && mapper.eligibleAgent(actor)==1;
            case MANAGED -> groups.qualificationCurrent(actor,"SUPERVISOR")!=null;
            case ALL -> mapper.roles(actor).stream().anyMatch(SupportOwnershipService::superRole);
        };
        if(!allowed) throw new BizException(403,"SUPPORT_SCOPE_FORBIDDEN");
        if(groupId!=null && groups.readableGroup(scope,groupId)==null)
            throw new BizException(404,"SUPPORT_GROUP_NOT_FOUND");
        if(agentId!=null && groups.readableAgent(scope,agentId)!=1)
            throw new BizException(404,"SUPPORT_AGENT_NOT_FOUND");
        // No owned groups is a valid empty MANAGED set, never a fallback to ALL or PERSONAL.
        return scope;
    }

    @Transactional
    public ReadScope defaultQueryScope(Long groupId, Long agentId) {
        Long actor=actorId();
        ReadMode mode=mapper.roles(actor).stream().anyMatch(SupportOwnershipService::superRole)?ReadMode.ALL
                :groups.qualificationCurrent(actor,"SUPERVISOR")!=null?ReadMode.MANAGED:ReadMode.PERSONAL;
        return queryScope(mode,groupId,agentId);
    }

    /** Mutating callers invoke this after their ordered customer/admin/group locks. */
    @Transactional
    public void requireManagingCustomer(Long customer) {
        Long actor=actorId();
        ReadMode mode=mapper.roles(actor).stream().anyMatch(SupportOwnershipService::superRole)?ReadMode.ALL:ReadMode.MANAGED;
        ReadScope scope=queryScope(mode,null,null);
        if(!validScopeId(customer) || mapper.readableCustomer(scope,customer)!=1)
            throw new BizException(404,"SUPPORT_CUSTOMER_NOT_FOUND");
    }

    /** Allocation target, not a directory read; null means a proven ungrouped member only for ALL. */
    @Transactional
    public void requireTargetMember(Long agent, Long group) {
        if(!validScopeId(agent) || (group!=null && !validScopeId(group)))
            throw new BizException(422,"SUPPORT_AGENT_UNAVAILABLE");
        Long actor=actorId();
        ReadMode mode=mapper.roles(actor).stream().anyMatch(SupportOwnershipService::superRole)?ReadMode.ALL:ReadMode.MANAGED;
        ReadScope scope=queryScope(mode,group,agent);
        Member member=groups.memberCurrent(agent);
        if(member==null || !java.util.Objects.equals(group,member.groupId())
                || groups.qualificationCurrent(agent,"SERVICE")==null || mapper.eligibleAgent(agent)!=1)
            throw new BizException(422,"SUPPORT_AGENT_UNAVAILABLE");
        if(group==null) {
            if(mode!=ReadMode.ALL) throw new BizException(403,"SUPPORT_GROUP_FORBIDDEN");
            return;
        }
        Group target=groups.readableGroup(scope,group);
        if(target==null || !"ENABLED".equals(target.status())
                || groups.readableGroup(new ReadScope(target.supervisorAdminId(),ReadMode.MANAGED,group,null),group)==null)
            throw new BizException(422,"SUPPORT_GROUP_TARGET_UNAVAILABLE");
    }

    @Transactional
    public boolean canReadAgent(Long actor, Long agent) {
        if(!validScopeId(actor) || !validScopeId(agent)) return false;
        if(actor.equals(agent) && (groups.qualificationCurrent(actor,"SERVICE")!=null
                || groups.qualificationCurrent(actor,"SUPERVISOR")!=null)) return true;
        return groups.readableAgent(new ReadScope(actor,ReadMode.ALL,null,null),agent)==1
                || groups.readableAgent(new ReadScope(actor,ReadMode.MANAGED,null,null),agent)==1;
    }

    @Transactional
    public void readTicket(String no) {
        Long customer=ticketCustomer(no);
        if(!canReadCurrent(actorId(),customer)) throw new BizException(404,"SUPPORT_CUSTOMER_NOT_FOUND");
    }

    /** One actually permitted object mode; it does not change a collection's chosen mode. */
    @Transactional
    public ReadScope customerQueryScope(Long customer) {
        ReadScope scope=currentCustomerScope(actorId(),customer);
        if(scope==null) throw new BizException(404,"SUPPORT_CUSTOMER_NOT_FOUND");
        return scope;
    }

    private boolean canReadCurrent(Long actor, Long customer) {
        return currentCustomerScope(actor,customer)!=null;
    }

    private ReadScope currentCustomerScope(Long actor,Long customer) {
        if(!validScopeId(actor) || !validScopeId(customer)) return null;
        for(ReadMode mode:List.of(ReadMode.ALL,ReadMode.MANAGED,ReadMode.PERSONAL)) {
            ReadScope scope=new ReadScope(actor,mode,null,null);
            if(mapper.readableCustomer(scope,customer)==1) return scope;
        }
        return null;
    }

    private static boolean validScopeId(Long id) { return id!=null && id>0 && id<=9007199254740991L; }

    public static boolean hasAuthority(String permission) {
        var auth=SecurityContextHolder.getContext().getAuthentication();
        return auth!=null && auth.isAuthenticated() && auth.getAuthorities().stream().anyMatch(a->permission.equals(a.getAuthority()));
    }

    public Long actorId() { return actorId(SecurityContextHolder.getContext().getAuthentication()); }

    public Long actorId(Authentication auth) {
        if (auth == null || !auth.isAuthenticated()) throw new BizException(401, "LOGIN_REQUIRED");
        try { return Long.valueOf(auth.getName()); }
        catch (NumberFormatException ex) { throw new BizException(401, "LOGIN_REQUIRED"); }
    }

    @Transactional
    public boolean supervisor(Long actor) {
        if(!validScopeId(actor))return false;
        List<String> roles = mapper.roles(actor);
        return roles.stream().anyMatch(SupportOwnershipService::superRole)
                || groups.qualificationCurrent(actor,"SUPERVISOR")!=null;
    }

    @Transactional
    public void requireSupervisor() {
        if (!supervisor(actorId())) throw new BizException(403, "SUPPORT_MANAGEMENT_FORBIDDEN");
    }
    /** Preflight only; mutating callers recheck after their ordered customer/rules/admin locks. */
    public void requireSupervisorSnapshot() {
        Long actor=actorId();List<String> roles=mapper.rolesSnapshot(actor);
        if(roles.stream().noneMatch(SupportOwnershipService::superRole)
                && groups.qualificationSnapshot(actor,"SUPERVISOR")==null)
            throw new BizException(403,"SUPPORT_MANAGEMENT_FORBIDDEN");
    }
    public void requireSuperAdminSnapshot() {
        if(mapper.rolesSnapshot(actorId()).stream().noneMatch(SupportOwnershipService::superRole))
            throw new BizException(403,"SUPPORT_RULES_FORBIDDEN");
    }
    private static boolean superRole(String role) {return "SUPER".equalsIgnoreCase(role) || "SUPERADMIN".equalsIgnoreCase(role) || "SUPER_ADMIN".equalsIgnoreCase(role);}

    @Transactional
    public boolean currentSuperAdmin() {
        return mapper.roles(actorId()).stream().anyMatch(SupportOwnershipService::superRole);
    }

    @Transactional
    public void requireSuperAdmin() {
        if (!currentSuperAdmin())
            throw new BizException(403, "SUPPORT_RULES_FORBIDDEN");
    }

    @Transactional
    public boolean canRead(Long actor, Long customer) {
        return canReadCurrent(actor,customer);
    }

    @Transactional
    public void requireRead(Long customer) {
        if (!canRead(actorId(), customer)) throw new BizException(404, "SUPPORT_CUSTOMER_NOT_FOUND");
    }

    public void requireEligibleAgent() {
        if (mapper.eligibleAgent(actorId()) != 1) throw new BizException(403,"SUPPORT_AGENT_UNAVAILABLE");
    }

    public void lockAgent(Long actor) {
        if (mapper.lockAgent(actor)==null) throw new BizException(422,"SUPPORT_AGENT_UNAVAILABLE");
    }

    public void lockWriters(java.util.stream.Stream<Long> customers) {
        var ids=customers.distinct().sorted().toList();
        ids.forEach(this::lockCustomer);
        ids.forEach(id->requireWriter(id,true));
    }

    public void requireHandled(String conversationNo) {
        if (mapper.pendingReplies(conversationNo) > 0) throw new BizException(409,"SUPPORT_REPLY_REQUIRED");
    }

    public Long conversationCustomer(String no) {
        Long id = mapper.conversationCustomer(no);
        if (id == null) throw new BizException(404, "CONVERSATION_NOT_FOUND");
        return id;
    }

    public Long ticketCustomer(String no) {
        Long id = mapper.ticketCustomer(no);
        if (id == null) throw new BizException(404, "TICKET_NOT_FOUND");
        return id;
    }

    @Transactional
    public void readConversation(String no) { requireRead(conversationCustomer(no)); }
    @Transactional
    public boolean canReadConversation(Long actor, String no) { return canRead(actor, mapper.conversationCustomer(no)); }

    /** Customer lock must precede conversation/message/cycle locks; S4 uses this same entry point. */
    public void lockCustomer(Long customer) {
        if (!TransactionSynchronizationManager.isActualTransactionActive())
            throw new IllegalStateException("SUPPORT_CUSTOMER_TRANSACTION_REQUIRED");
        if (customer == null || mapper.lockCustomer(customer) == null) throw new BizException(404, "SUPPORT_CUSTOMER_NOT_FOUND");
    }

    /** Authorize the immutable customer key before taking any conversation/header locks. */
    public void lockCustomerConversation(Long customer,String no) {
        if(customer==null || !customer.equals(conversationCustomer(no))) throw new BizException(404,"CONVERSATION_NOT_FOUND");
        lockCustomer(customer);
    }

    public SupportAssignment requireWriter(Long customer, boolean lock) {
        if (lock) lockCustomer(customer);
        Long actor = actorId();
        if (lock && mapper.lockAgent(actor) == null) throw new BizException(403,"SUPPORT_AGENT_UNAVAILABLE");
        SupportAssignment assignment = mapper.current(customer);
        if (assignment == null || !actor.equals(assignment.agentAdminId()) || mapper.eligibleAgent(actor) != 1)
            throw new BizException(404, "SUPPORT_CUSTOMER_NOT_FOUND");
        return assignment;
    }

    /** Internal persisted-actor authorization; never derives permission from an ambient session. */
    public void requireSendingActor(Long actor) {
        if (!TransactionSynchronizationManager.isActualTransactionActive())
            throw new IllegalStateException("SUPPORT_WRITER_TRANSACTION_REQUIRED");
        if (actor == null || actor <= 0 || actor > 9007199254740991L
                || mapper.lockAgent(actor) == null || mapper.eligibleAgent(actor) != 1)
            throw new BizException(403,"SUPPORT_AGENT_UNAVAILABLE");
        if (mapper.writerGrant(actor).isEmpty()) throw new BizException(403,"SUPPORT_WRITE_FORBIDDEN");
    }

    public SupportAssignment requireWriterForActor(Long actor, Long customer, boolean lock) {
        if (lock) lockCustomer(customer);
        requireSendingActor(actor);
        SupportAssignment assignment = mapper.current(customer);
        if (assignment == null || !actor.equals(assignment.agentAdminId()))
            throw new BizException(404,"SUPPORT_CUSTOMER_NOT_FOUND");
        return assignment;
    }

    public void writeConversation(String no, boolean lock) { requireWriter(conversationCustomer(no), lock); }
    public void writeTicket(String no, boolean lock) { requireWriter(ticketCustomer(no), lock); }
}
