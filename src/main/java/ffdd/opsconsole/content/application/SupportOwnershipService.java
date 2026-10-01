package ffdd.opsconsole.content.application;

import ffdd.opsconsole.common.boundary.ApplicationService;
import ffdd.opsconsole.content.domain.SupportAssignment;
import ffdd.opsconsole.content.mapper.SupportBindingMapper;
import ffdd.opsconsole.shared.exception.BizException;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/** Shared object authorization for HTTP, replay, sockets, streams and private attachments. */
@ApplicationService
@RequiredArgsConstructor
public class SupportOwnershipService {
    private final SupportBindingMapper mapper;

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

    public boolean supervisor(Long actor) {
        List<String> roles = mapper.roles(actor);
        return roles.stream().anyMatch(r -> "SUPER".equalsIgnoreCase(r) || "SUPERADMIN".equalsIgnoreCase(r) || "SUPER_ADMIN".equalsIgnoreCase(r))
                || (roles.stream().anyMatch("SUPPORT"::equalsIgnoreCase) && mapper.supervisorProfile(actor) == 1);
    }

    public void requireSupervisor() {
        if (!supervisor(actorId())) throw new BizException(403, "SUPPORT_MANAGEMENT_FORBIDDEN");
    }
    /** Preflight only; mutating callers recheck after their ordered customer/rules/admin locks. */
    public void requireSupervisorSnapshot() {
        Long actor=actorId();List<String> roles=mapper.rolesSnapshot(actor);
        if(roles.stream().noneMatch(SupportOwnershipService::superRole)
                && !(roles.stream().anyMatch("SUPPORT"::equalsIgnoreCase) && mapper.supervisorProfileSnapshot(actor)==1))
            throw new BizException(403,"SUPPORT_MANAGEMENT_FORBIDDEN");
    }
    public void requireSuperAdminSnapshot() {
        if(mapper.rolesSnapshot(actorId()).stream().noneMatch(SupportOwnershipService::superRole))
            throw new BizException(403,"SUPPORT_RULES_FORBIDDEN");
    }
    private static boolean superRole(String role) {return "SUPER".equalsIgnoreCase(role) || "SUPERADMIN".equalsIgnoreCase(role) || "SUPER_ADMIN".equalsIgnoreCase(role);}

    public void requireSuperAdmin() {
        if (mapper.roles(actorId()).stream().noneMatch(r -> "SUPER".equalsIgnoreCase(r) || "SUPERADMIN".equalsIgnoreCase(r) || "SUPER_ADMIN".equalsIgnoreCase(r)))
            throw new BizException(403, "SUPPORT_RULES_FORBIDDEN");
    }

    public boolean canRead(Long actor, Long customer) {
        if (actor == null || customer == null) return false;
        return supervisor(actor) || (mapper.eligibleAgent(actor) == 1 && actor.equals(mapper.currentAgent(customer)));
    }

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

    public void readConversation(String no) { requireRead(conversationCustomer(no)); }
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
