package ffdd.opsconsole.content.application;

import ffdd.opsconsole.common.boundary.ApplicationService;
import ffdd.opsconsole.content.domain.SupportAssignment;
import ffdd.opsconsole.content.mapper.SupportBindingMapper;
import ffdd.opsconsole.content.mapper.SupportReceptionMapper;
import ffdd.opsconsole.content.mapper.SupportReceptionMapper.Hold;
import ffdd.opsconsole.shared.api.ApiResult;
import ffdd.opsconsole.shared.audit.AuditLogService;
import ffdd.opsconsole.shared.audit.AuditLogWriteRequest;
import ffdd.opsconsole.shared.exception.BizException;
import ffdd.opsconsole.shared.idempotency.AdminIdempotencyService;
import java.time.Clock;
import java.time.LocalDateTime;
import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

@ApplicationService
@RequiredArgsConstructor
public class SupportSessionService {
    private final SupportReceptionMapper sessions;
    private final SupportOwnershipService ownership;
    private final SupportBindingMapper bindings;
    private final AdminIdempotencyService commands;
    private final AuditLogService audit;
    private final ApplicationEventPublisher events;
    private final Clock clock;
    private final PlatformTransactionManager transactions;
    private final ProductionSupportPathGuard production;

    public record Holder(Long adminId,String name,String role) {}
    public record State(String state,Holder holder,String lastSentAt,String autoEndAt,int endMinutes) {}
    public static class Busy extends BizException {
        private final State state;
        Busy(State state) { super(409,"SUPPORT_SESSION_BUSY"); this.state=state; }
        public State state() {return state;}
    }

    @Transactional
    public State state(long customer) {
        requireBoundRead(customer);
        return view(ownership.actorId(),active(customer));
    }

    private void requireBoundRead(long customer) {
        ownership.lockCustomer(customer);
        ownership.requireRead(customer);
        production.requireAllowed(customer);
        if(bindings.current(customer)==null) throw new BizException(404,"SUPPORT_CUSTOMER_NOT_FOUND");
    }

    public Map<Long,Holder> holders(Collection<Long> customers) {
        Map<Long,Holder> result=new HashMap<>();
        if(!customers.isEmpty()) for(Hold hold:sessions.holds(customers)) {
            if(hold.active()) result.put(hold.customerId(),holder(hold));
        }
        return result;
    }

    private Holder holder(Hold row) {return new Holder(row.adminId(),row.name(),row.role());}
    private State view(long viewer,Hold row) {
        if(row==null) return new State("FREE",null,null,null,sessions.endMinutes());
        return new State(row.adminId()==viewer?"MINE":"OTHER",holder(row),
                row.lastSentAt().toInstant(java.time.ZoneOffset.UTC).toString(),row.autoEndAt().toInstant(java.time.ZoneOffset.UTC).toString(),row.endMinutes());
    }

    /** Caller holds nx_user FOR UPDATE. Both clocks and the expiry predicate are evaluated by MySQL. */
    private Hold active(long customer) {
        Hold row=sessions.hold(customer);
        if(row==null || row.active()) return row;
        if(sessions.end(customer,row.autoEndAt(),"IDLE")==1) ended(row,"IDLE");
        return null;
    }

    public void check(SupportAssignment assignment,long actor,String role,String intent) {
        if(!"ADVISOR".equals(role) && "MAINTENANCE".equals(intent))
            throw new BizException(422,"SUPPORT_SESSION_MAINTENANCE_NOT_ALLOWED");
        Hold row=active(assignment.customerId());
        if(row!=null && row.adminId()!=actor) throw new Busy(view(actor,row));
        if(row!=null && !row.assignmentId().equals(assignment.id())) throw new BizException(409,"SUPPORT_ASSIGNMENT_CHANGED");
    }

    public void sent(SupportAssignment assignment,long actor,String name,String role,String no) {
        sessions.receive(assignment.customerId(),assignment.id(),actor,name,role,no);
        // The normal message invalidation is published after commit and includes the updated hold.
    }

    @Transactional
    @SuppressWarnings({"unchecked","rawtypes"})
    public ApiResult<?> end(long customer,String key) {
        requireBoundRead(customer);
        long actor=ownership.actorId();
        if(bindings.writerGrant(actor).isEmpty()) throw new BizException(403,"SUPPORT_WRITE_FORBIDDEN");
        SupportAttachmentService.token(key);
        return commands.executeRetained("M3_SESSION_END:"+actor,key,Long.toString(customer),ApiResult.class,()->{
            Hold row=active(customer);
            if(row==null || row.adminId()!=actor) throw new BizException(409,"SUPPORT_SESSION_NOT_HOLDER");
            sessions.end(customer,null,"MANUAL");
            ended(row,"MANUAL");
            return ApiResult.ok(Map.of("customerId",customer));
        });
    }

    public void resolve(long customer) {
        Hold row=active(customer);
        if(row==null) return;
        long actor=ownership.actorId();
        if(row.adminId()!=actor) {
            rejected(customer,"M3_SESSION_RESOLVE","SUPPORT_SESSION_BUSY");
            throw new Busy(view(actor,row));
        }
        sessions.end(customer,null,"RESOLVED");
        ended(row,"RESOLVED");
    }

    private void ended(Hold row,String reason) {
        audit.recordRequired(AuditLogWriteRequest.builder().action("SUPPORT_SESSION_ENDED").resourceType("SUPPORT_SESSION")
                .resourceId(row.customerId().toString()).actorType("IDLE".equals(reason)?"SYSTEM":"ADMIN")
                .actorUsername("IDLE".equals(reason)?"system":"admin:"+row.adminId()).result("SUCCESS")
                .detail(Map.of("reason",reason,"holder",holder(row),"conversationNo",row.conversationNo())).build());
        OpsConversationAfterCommitPublisher.publish(events,ConversationMessageEvent.builder()
                .conversationNo(row.conversationNo()).eventType(ConversationMessageEvent.EventType.STATUS)
                .senderType("SYSTEM").senderName("系统").body("SESSION_ENDED").ts(LocalDateTime.now(clock)).build());
    }

    public void rejected(Long customer,String operation,String error) {
        Hold hold=customer==null?null:sessions.hold(customer);
        Map<String,Object> detail=new LinkedHashMap<>();
        detail.put("operation",operation);
        detail.put("code",error);
        if(hold!=null && hold.active()) detail.put("holder",holder(hold));
        audit.recordRequiredInNewTransaction(AuditLogWriteRequest.builder().action("SUPPORT_SESSION_REJECTED")
                .resourceType("SUPPORT_SESSION").resourceId(String.valueOf(customer)).actorType("ADMIN")
                .actorUsername("admin:"+ownership.actorId()).result("REJECTED").detail(detail).build());
    }

    @Transactional(readOnly=true)
    public boolean canType(long actor,long customer) {
        var assignment=bindings.current(customer);
        if(ownership.messageRole(actor,assignment)==null || bindings.writerGrant(actor).isEmpty()) return false;
        Hold row=sessions.hold(customer);
        return row==null || !row.active() || row.adminId()==actor && row.assignmentId().equals(assignment.id());
    }

    @Scheduled(fixedDelayString="${nexgrid.support.session-expiry-ms:15000}")
    public void expireDue() {
        if(!production.productionSupportAutomationAllowed()) return;
        var tx=new TransactionTemplate(transactions);
        for(long customer:sessions.due()) tx.executeWithoutResult(status->{
            ownership.lockCustomer(customer);
            active(customer);
        });
    }
}
