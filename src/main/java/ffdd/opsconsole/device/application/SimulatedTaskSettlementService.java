package ffdd.opsconsole.device.application;

import ffdd.opsconsole.device.mapper.AppTaskAssignmentMapper;
import ffdd.opsconsole.device.mapper.SimulatedTaskMapper.TaskRow;
import ffdd.opsconsole.device.mapper.SimulatedTaskSettlementMapper;
import ffdd.opsconsole.shared.audit.AuditLogService;
import ffdd.opsconsole.shared.audit.AuditLogWriteRequest;
import ffdd.opsconsole.shared.config.DateTimeFormatConfig;
import ffdd.opsconsole.shared.exception.BizException;
import ffdd.opsconsole.shared.outbox.EventOutboxService;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/** Receipt, original financial records and the next task share the caller's SQL transaction. */
@Service
@RequiredArgsConstructor
public class SimulatedTaskSettlementService {
    private final SimulatedTaskSettlementMapper mapper;
    private final AppTaskAssignmentMapper assignments;
    private final SupportDeviceFundAdapter funds;
    private final DeviceMonthlyTargetService monthlyTargets;
    private final EventOutboxService outbox;
    private final AuditLogService audit;

    @Transactional(propagation=Propagation.MANDATORY)
    public void settle(TaskRow task,LocalDateTime postedAt) {
        var wallet=mapper.wallet(task.userId());
        if(wallet==null || ((Number)wallet.get("id")).longValue()!=task.walletId()
                || !task.walletIncarnation().equals(wallet.get("incarnation")))
            throw new BizException(409,"SIMULATED_TASK_WALLET_CHANGED");
        String day=task.dueAt().atZone(DateTimeFormatConfig.BUSINESS_ZONE).withZoneSameInstant(ZoneOffset.UTC).toLocalDate().toString();
        String dailyKey="CLOUD_SHARE_DAILY:"+task.deviceId()+":"+day;
        BigDecimal daily=task.cloudDailyNex().signum()>0 && assignments.lockDailyCloudShareNex(task.userId(),dailyKey)==null
                ? task.cloudDailyNex() : BigDecimal.ZERO;
        BigDecimal usdt="USDT".equals(task.asset())?task.rewardAmount():BigDecimal.ZERO;
        BigDecimal baseNex="UVT".equals(task.asset())?task.rewardAmount():BigDecimal.ZERO;
        BigDecimal nex=baseNex.add(daily);
        mapper.task(task,usdt,postedAt);
        mapper.receipt(task,usdt,nex,postedAt);
        var before=funds.begin(task.userId());
        if(usdt.signum()>0) {
            if(mapper.credit(task,"USDT",usdt,postedAt)!=1)throw new BizException(409,"SIMULATED_TASK_CREDIT_CONFLICT");
            mapper.ledger(task.userId(),task.taskNo(),"USDT",usdt,mapper.usdt(task.userId()),postedAt);
            mapper.earning(task,"EARN-"+task.taskNo(),"USDT",usdt,postedAt);
        }
        funds.afterUsdt(before);
        if(nex.signum()>0) {
            if(mapper.credit(task,"UVT",nex,postedAt)!=1)throw new BizException(409,"SIMULATED_TASK_CREDIT_CONFLICT");
            BigDecimal balance=mapper.nex(task.userId());
            if(baseNex.signum()>0)mapper.ledger(task.userId(),task.taskNo(),"UVT",baseNex,balance,postedAt);
            if(daily.signum()>0)mapper.ledger(task.userId(),dailyKey,"UVT",daily,balance,postedAt);
            mapper.earning(task,"EARN-NEX-"+task.receiptNo(),"UVT",nex,postedAt);
        }
        monthlyTargets.credit(task,usdt);
        funds.recorded(before,task.receiptNo());
        var attribution=assignments.userEventAttribution(task.userId());
        if(attribution==null)throw new BizException(409,"TASK_ASSIGNMENT_EVENT_ATTRIBUTION_UNAVAILABLE");
        var payload=Map.<String,Object>of("task_id",task.taskTypeId(),"task_no",task.taskNo(),"device_id",task.deviceId(),
                "receipt_no",task.receiptNo(),"amount_usdt",usdt);
        outbox.publishUserEvent("COMPUTE_TASK",task.taskNo(),"task.completed",task.userId(),attribution.phase(),attribution.accountAgeMonths(),attribution.cohort(),payload);
        outbox.publishUserEvent("COMPUTE_TASK",task.taskNo(),"earnings.credited",task.userId(),attribution.phase(),attribution.accountAgeMonths(),attribution.cohort(),payload);
        audit.recordRequiredForTrustedActor(AuditLogWriteRequest.builder().action("TASK_ASSIGNMENT_COMPLETED")
                .resourceType("COMPUTE_TASK").resourceId(task.taskNo()).bizNo(task.receiptNo()).userId(task.userId())
                .actorId(0L).actorType("SYSTEM").actorUsername("system").method("SCHEDULED")
                .path("/internal/simulated-tasks/settle").result("SUCCESS").riskLevel("HIGH")
                .detail(Map.of("executionMode","SIMULATED","taskNo",task.taskNo(),"deviceId",task.deviceId(),
                        "receiptNo",task.receiptNo(),"asset",task.asset(),"reward",task.rewardAmount(),"completedAt",task.dueAt(),"postedAt",postedAt)).build());
    }
}
