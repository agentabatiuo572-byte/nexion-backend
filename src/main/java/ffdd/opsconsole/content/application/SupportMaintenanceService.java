package ffdd.opsconsole.content.application;

import ffdd.opsconsole.common.boundary.ApplicationService;
import ffdd.opsconsole.content.domain.SupportAssignment;
import ffdd.opsconsole.content.domain.SupportMaintenance.*;
import ffdd.opsconsole.content.mapper.SupportBindingMapper;
import ffdd.opsconsole.content.mapper.SupportMaintenanceMapper;
import ffdd.opsconsole.shared.api.ApiResult;
import ffdd.opsconsole.shared.audit.AuditLogService;
import ffdd.opsconsole.shared.audit.AuditLogWriteRequest;
import ffdd.opsconsole.shared.exception.BizException;
import ffdd.opsconsole.shared.idempotency.AdminIdempotencyService;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.*;
import lombok.RequiredArgsConstructor;
import org.springframework.context.event.EventListener;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@ApplicationService
@RequiredArgsConstructor
public class SupportMaintenanceService {
    private final SupportMaintenanceMapper mapper;
    private final SupportBindingMapper bindings;
    private final SupportOwnershipService ownership;
    private final AdminIdempotencyService idempotency;
    private final AuditLogService audit;

    public Preference preference(Long customer) {
        ownership.requireRead(customer);
        return currentPreference(customer);
    }

    private Preference currentPreference(Long customer) {
        Preference found=mapper.preference(customer);
        return found==null ? new Preference(customer,true,1L) : found;
    }

    /** Called only after a human MAINTENANCE message has been inserted in this same transaction. */
    @Transactional(propagation=Propagation.MANDATORY)
    public void executed(Long customer, SupportAssignment assignment, Long messageId, String commandKey) {
        SupportAssignment current=ownership.requireWriter(customer,true);
        executedWithAssignment(customer,current,assignment,messageId,commandKey);
    }

    @Transactional(propagation=Propagation.MANDATORY)
    public void executedForActor(Long customer,Long actor,SupportAssignment assignment,Long messageId,String commandKey) {
        SupportAssignment current=ownership.requireWriterForActor(actor,customer,true);
        executedWithAssignment(customer,current,assignment,messageId,commandKey);
    }

    private void executedWithAssignment(Long customer,SupportAssignment current,SupportAssignment assignment,Long messageId,String commandKey) {
        if(assignment==null || !Objects.equals(current.id(),assignment.id()))
            throw new BizException(409,"SUPPORT_ASSIGNMENT_CHANGED");
        if(!safeId(messageId) || commandKey==null || commandKey.trim().length()<8 || commandKey.trim().length()>128)
            throw new BizException(422,"SUPPORT_MAINTENANCE_MESSAGE_REQUIRED");
        Long existing=mapper.executionCustomer(messageId);
        if(existing!=null) {
            if(!existing.equals(customer)) throw new BizException(409,"SUPPORT_MAINTENANCE_MESSAGE_CONFLICT");
            return;
        }
        if(!currentPreference(customer).enabled()) throw new BizException(409,"SUPPORT_MAINTENANCE_STOPPED");
        LocalDateTime at=captureTime();
        Cycle cycle=mapper.openCycle(customer);
        if(cycle==null) {
            ActivityState activity=mapper.activity(customer);
            mapper.insertCycle(customer,current.id(),current.agentAdminId(),activity==null?0:activity.activitySeq(),at);
            cycle=mapper.openCycle(customer);
        }
        if(!cycle.assignmentId().equals(current.id())) throw new BizException(409,"SUPPORT_CYCLE_ASSIGNMENT_CHANGED");
        mapper.insertExecution(customer,current.id(),current.agentAdminId(),cycle.id(),messageId,commandKey.trim(),at);
        if(mapper.touchCycle(cycle.id(),at)!=1) throw new BizException(409,"SUPPORT_CYCLE_CHANGED");
    }

    @Transactional(propagation=Propagation.MANDATORY)
    public void effectiveActivity(ActivityEvent event) {
        ownership.lockCustomer(event.customerId());
        Cycle cycle=mapper.openCycle(event.customerId());
        SupportAssignment current=bindings.current(event.customerId());
        if(cycle!=null && current!=null && currentPreference(event.customerId()).enabled()
                && cycle.assignmentId().equals(current.id()) && event.seq()>cycle.baselineActivitySeq()
                && !event.occurredAt().isBefore(cycle.openedAt())) {
            if(mapper.closeCycle(cycle.id(),"SUCCEEDED",event.occurredAt(),event.id())!=1)
                throw new BizException(409,"SUPPORT_CYCLE_CHANGED");
        }
    }

    @EventListener
    @Transactional(propagation=Propagation.MANDATORY)
    public void assignmentChanged(SupportBindingService.SupportAssignmentChanged event) {
        ownership.lockCustomer(event.customerId());
        Cycle cycle=mapper.openCycle(event.customerId());
        if(cycle!=null && !cycle.assignmentId().equals(event.current().id()))
            mapper.closeCycle(cycle.id(),"TRANSFERRED",captureTime(),null);
    }

    @SuppressWarnings({"unchecked","rawtypes"})
    public ApiResult<Map<String,Object>> change(Long customer, Boolean enabled, String reason,
            Long expectedAssignmentId, Long expectedVersion, String key) {
        SupportBindingService.validateCommand(key,reason);
        if(enabled==null || !safeId(customer) || !safeId(expectedAssignmentId) || !safeId(expectedVersion))
            throw new BizException(422,"SUPPORT_MAINTENANCE_EXPECTATION_REQUIRED");
        ownership.requireWriter(customer,false); // Recheck current scope even when returning an old receipt.
        String request=customer+"|"+enabled+"|"+expectedAssignmentId+"|"+expectedVersion+"|"+reason.trim();
        String digest;
        try {digest=HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(request.getBytes(StandardCharsets.UTF_8)));}
        catch(java.security.NoSuchAlgorithmException ex){throw new IllegalStateException(ex);}
        return (ApiResult<Map<String,Object>>)idempotency.executeRetained("M3_MAINTENANCE:"+ownership.actorId(),key,digest,ApiResult.class,()->{
            SupportAssignment assignment=ownership.requireWriter(customer,true);
            if(!assignment.id().equals(expectedAssignmentId)) throw new BizException(409,"SUPPORT_ASSIGNMENT_CHANGED");
            mapper.initializePreference(customer);
            Preference before=currentPreference(customer);
            if(!before.version().equals(expectedVersion)) throw new BizException(409,"SUPPORT_MAINTENANCE_VERSION_CONFLICT");
            if(mapper.changePreference(customer,enabled,ownership.actorId(),reason.trim(),expectedVersion)!=1)
                throw new BizException(409,"SUPPORT_MAINTENANCE_VERSION_CONFLICT");
            Cycle open=mapper.openCycle(customer);
            if(!enabled && open!=null) mapper.closeCycle(open.id(),"STOPPED",captureTime(),null);
            Preference after=currentPreference(customer);
            audit.recordRequired(AuditLogWriteRequest.builder().action(enabled?"SUPPORT_MAINTENANCE_RESUMED":"SUPPORT_MAINTENANCE_STOPPED")
                    .resourceType("SUPPORT_MAINTENANCE").resourceId(String.valueOf(customer)).bizNo(String.valueOf(customer))
                    .actorType("ADMIN").actorUsername(String.valueOf(ownership.actorId())).result("SUCCESS").riskLevel("HIGH")
                    .detail(Map.of("before",before,"after",after,"reason",reason.trim(),"commandKey",key.trim(),"assignmentId",assignment.id())).build());
            return ApiResult.ok(SupportWorkbenchService.wire(Map.of("customerId",customer,"assignmentId",assignment.id(),"enabled",after.enabled(),"version",after.version())));
        });
    }

    @Transactional(readOnly=true)
    public Map<String,Object> history(Long customer, long pageNum, int pageSize) {
        ownership.requireRead(customer);
        if(!safeId(customer) || pageNum<1 || pageNum>9007199254740991L || pageSize<1 || pageSize>100 || pageNum>Long.MAX_VALUE/pageSize)
            throw new BizException(422,"SUPPORT_PAGE_INVALID");
        long offset=(pageNum-1)*pageSize;
        List<Map<String,Object>> cycles=mapper.cycles(customer,offset,pageSize).stream().map(c->{
            Map<String,Object> row=new LinkedHashMap<>();
            row.put("id",c.id());row.put("customerId",c.customerId());row.put("assignmentId",c.assignmentId());row.put("agentAdminId",c.agentAdminId());
            row.put("status",c.status());row.put("baselineActivitySeq",c.baselineActivitySeq());row.put("successEventId",c.successEventId());
            row.put("openedAt",c.openedAt().atOffset(ZoneOffset.UTC));row.put("lastExecutionAt",c.lastExecutionAt().atOffset(ZoneOffset.UTC));
            row.put("closedAt",c.closedAt()==null?null:c.closedAt().atOffset(ZoneOffset.UTC));return row;
        }).toList();
        List<Map<String,Object>> executions=mapper.executions(customer,offset,pageSize);
        executions.forEach(row->{Object at=row.get("executedAt");
            if(at instanceof LocalDateTime local)row.put("executedAt",local.atOffset(ZoneOffset.UTC));
            else if(at instanceof java.sql.Timestamp timestamp)row.put("executedAt",timestamp.toLocalDateTime().atOffset(ZoneOffset.UTC));
        });
        return SupportWorkbenchService.wire(Map.of("customerId",customer,"cycles",cycles,"executions",executions,"totalCycles",mapper.cycleCount(customer),
                "totalExecutions",mapper.executionCount(customer),"pageNum",pageNum,"pageSize",pageSize));
    }

    private static boolean safeId(Long value) {return value!=null && value>0 && value<=9007199254740991L;}

    private LocalDateTime captureTime() {
        if(mapper.captureFence()==null) throw new BizException(503,"SUPPORT_ACTIVITY_CAPTURE_UNAVAILABLE");
        return mapper.eventTime();
    }
}
