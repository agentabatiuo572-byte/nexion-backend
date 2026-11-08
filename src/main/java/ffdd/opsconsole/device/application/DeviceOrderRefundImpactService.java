package ffdd.opsconsole.device.application;

import ffdd.opsconsole.device.domain.DeviceOrderDetailView.OrderDevice;
import ffdd.opsconsole.device.domain.DeviceOrderDetailView.RefundImpact;
import ffdd.opsconsole.device.domain.DeviceOrderDetailView.TaskEarnings;
import ffdd.opsconsole.device.mapper.DeviceOrderRefundImpactMapper;
import ffdd.opsconsole.shared.exception.BizException;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.LocalDateTime;
import java.util.List;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class DeviceOrderRefundImpactService {
    private final DeviceOrderRefundImpactMapper mapper;
    private final ComputeV2ReadService compute;
    private final Clock clock;

    @Transactional(readOnly=true,isolation=Isolation.REPEATABLE_READ)
    public RefundImpact read(String orderNo,long userId,String orderType,List<OrderDevice> devices) {
        if("TRIAL_CONVERT".equalsIgnoreCase(orderType))return null;
        LocalDateTime asOf=LocalDateTime.now(clock);
        var ids=devices.stream().filter(device->!"PROMOTION_GIFT".equals(device.kind()))
                .map(OrderDevice::deviceId).collect(Collectors.toSet());
        ComputeV2ReadService.RefundRuntime runtime=null;
        String unavailable=null;
        try { runtime=compute.refundRuntime(userId,ids); }
        catch(RuntimeException failure) {
            unavailable=failure instanceof BizException business ? business.getMessage() : "COMPUTE_V2_STATE_UNAVAILABLE";
        }
        var totals=mapper.totals(orderNo,userId,asOf);
        if(totals.unresolvedSimulatedCount()>0) {
            runtime=null;
            unavailable="SIMULATED_TASK_RUNTIME_UNRESOLVED";
        }
        TaskEarnings pending=null;
        Long unfinished=null;
        if(runtime!=null) {
            var earned=runtime.completedPending();
            pending=new TaskEarnings(Math.addExact(totals.completedPendingCount(),earned.pendingTaskCount()),
                    totals.pendingUsdt().add(BigDecimal.valueOf(earned.pendingUsdtMicros(),6)),
                    totals.pendingUvt().add(BigDecimal.valueOf(earned.pendingNexMicros(),6)));
            unfinished=Math.addExact(totals.unfinishedCount(),runtime.unfinishedTaskCount());
        }
        return new RefundImpact("ORDINARY_PURCHASE",asOf,pending,unfinished,totals.stoppedCount(),
                new TaskEarnings(totals.creditedCount(),totals.creditedUsdt(),totals.creditedUvt()),
                runtime!=null,unavailable);
    }
}
