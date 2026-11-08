package ffdd.opsconsole.device.application;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import ffdd.opsconsole.device.mapper.DeviceMonthlyTargetMapper;
import ffdd.opsconsole.device.mapper.DeviceMonthlyTargetMapper.Row;
import ffdd.opsconsole.device.mapper.SimulatedTaskMapper.DeviceRow;
import ffdd.opsconsole.device.mapper.SimulatedTaskMapper.TaskRow;
import ffdd.opsconsole.shared.capacity.E3DeviceCapacityPolicy;
import ffdd.opsconsole.shared.exception.BizException;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class DeviceMonthlyTargetService {
    private final DeviceMonthlyTargetMapper mapper;
    private final ObjectMapper json;
    public record Quote(LocalDateTime cycleStart, String snapshotJson, BigDecimal reward, BigDecimal capacityPct) { }

    /** Caller holds the existing user/device/runtime/wallet locks. Generation never keys a cycle. */
    @Transactional(propagation=Propagation.MANDATORY)
    public Quote reserve(DeviceRow device,SimulatedTaskPolicyService.PolicyVersion policy,LocalDateTime start,
            SimulatedTaskPolicyService.TaskTypeSnapshot type,List<SimulatedTaskPolicyService.TaskTypeSnapshot> pool) {
        return reserve(device,policy,start,type,pool,null);
    }
    @Transactional(propagation=Propagation.MANDATORY)
    public Quote reserve(DeviceRow device,SimulatedTaskPolicyService.PolicyVersion policy,LocalDateTime start,
            SimulatedTaskPolicyService.TaskTypeSnapshot type,List<SimulatedTaskPolicyService.TaskTypeSnapshot> pool,
            LocalDateTime knownCycleStart) {
        if(pool.stream().anyMatch(t->!DeviceMonthlyTargetPacing.eligible(t.asset(),t.baseReward(),t.durationSeconds())))
            throw new MonthlyTargetPoolException(List.of(device.productCode()));
        LocalDateTime due=start.plusSeconds(type.durationSeconds());
        var anchor=device.purchasedAt()!=null?device.purchasedAt():device.activatedAt();
        var cycle=DeviceMonthlyTargetPacing.cycleAt(anchor,due);
        var capacity=E3DeviceCapacityPolicy.project(device.productCode(),device.deviceType(),anchor,device.activatedAt(),cycle.start(),policy.e3Config()).capacityPct();
        // A settled predecessor proves this PK exists. Never lock a potentially missing key under RR.
        if(!cycle.start().equals(knownCycleStart)) {
            var schedule=DeviceMonthlyTargetPacing.schedule(device.deviceId(),cycle);
            mapper.insert(new Row(device.deviceId(),cycle.start(),cycle.end(),cycle.index(),device.userId(),device.instanceNo(),
                    DeviceMonthlyTargetPacing.ALGORITHM,schedule.u(),encode(schedule.weights().stream().map(BigDecimal::toPlainString).toList()),BigDecimal.ZERO,BigDecimal.ZERO,BigDecimal.ZERO,
                    BigDecimal.ZERO,BigDecimal.ZERO,policy.version(),policy.e3Version(),policy.monthlyTargetUsdt(),policy.monthlyTargetBandPct(),capacity,false));
        }
        var row=mapper.lock(device.deviceId(),cycle.start());
        requireIdentity(row,device.userId(),device.instanceNo());
        var schedule=new DeviceMonthlyTargetPacing.Schedule(cycle,row.u(),weights(row.weightsJson()));
        var state=new DeviceMonthlyTargetPacing.State(row.p(),row.lo(),row.hi(),row.allocatedUsdt());
        BigDecimal base=BigDecimal.ZERO;long duration=0;
        for(var t:pool) {base=base.add(t.baseReward());duration+=t.durationSeconds();}
        var snapshot=DeviceMonthlyTargetPacing.quote(schedule,state,start,due,policy.monthlyTargetUsdt(),policy.monthlyTargetBandPct(),capacity,
                type.baseReward(),type.durationSeconds(),DeviceMonthlyTargetPacing.divide(base,BigDecimal.valueOf(duration)));
        save(row,state.add(snapshot),row.configChanged() || row.firstMonthlyTargetUsdt().compareTo(policy.monthlyTargetUsdt())!=0
                ||row.firstMonthlyTargetBandPct().compareTo(policy.monthlyTargetBandPct())!=0||row.firstCapacityPct().compareTo(capacity)!=0
                ||row.firstE3Version()!=policy.e3Version());
        return new Quote(cycle.start(),encode(DeviceMonthlyTargetPacing.compact(snapshot)),snapshot.reward(),capacity);
    }
    @Transactional(propagation=Propagation.MANDATORY)
    public void cancel(TaskRow task) {
        if(task.targetSnapshotJson()==null)return;
        var snapshot=snapshot(task.targetSnapshotJson());
        var row=mapper.lock(task.deviceId(),task.targetCycleStart());
        requireIdentity(row,task.userId(),task.instanceNo());
        save(row,new DeviceMonthlyTargetPacing.State(row.p().subtract(snapshot.p()),row.lo().subtract(snapshot.lo()),row.hi().subtract(snapshot.hi()),row.allocatedUsdt().subtract(task.rewardAmount())),row.configChanged());
    }
    @Transactional(propagation=Propagation.MANDATORY)
    public void credit(TaskRow task, BigDecimal usdt) {
        if(task.targetSnapshotJson()==null)return;
        if(!"USDT".equals(task.asset()) || usdt.compareTo(task.rewardAmount())!=0
                ||mapper.credit(task.deviceId(),task.targetCycleStart(),task.userId(),task.instanceNo(),usdt)!=1)
            throw new BizException(409,"MONTHLY_TARGET_CREDIT_CONFLICT");
    }
    private static void requireIdentity(Row row,long user,String instance) {
        if(row==null || row.userId()!=user || !row.instanceNo().equals(instance) || !DeviceMonthlyTargetPacing.ALGORITHM.equals(row.algorithm()))
            throw new BizException(409,"MONTHLY_TARGET_CYCLE_IDENTITY_CONFLICT");
    }
    private void save(Row r,DeviceMonthlyTargetPacing.State s,boolean changed) {
        if(mapper.allocate(new Row(r.deviceId(),r.cycleStart(),r.cycleEnd(),r.cycleIndex(),r.userId(),r.instanceNo(),r.algorithm(),r.u(),r.weightsJson(),
                s.p(),s.lo(),s.hi(),s.allocated(),r.creditedUsdt(),r.firstProductVersion(),r.firstE3Version(),r.firstMonthlyTargetUsdt(),r.firstMonthlyTargetBandPct(),r.firstCapacityPct(),changed))!=1)
            throw new BizException(409,"MONTHLY_TARGET_ALLOCATION_CONFLICT");
    }
    public DeviceMonthlyTargetPacing.Evidence snapshot(String value) {
        try {return DeviceMonthlyTargetPacing.evidence(json.readValue(value,String.class));}
        catch(JsonProcessingException failure){throw new IllegalStateException("MONTHLY_TARGET_SNAPSHOT_INVALID",failure);}
    }
    private List<BigDecimal> weights(String value) {
        try {return json.readValue(value,new TypeReference<List<BigDecimal>>(){});}
        catch(JsonProcessingException failure){throw new IllegalStateException("MONTHLY_TARGET_WEIGHTS_INVALID",failure);}
    }
    private String encode(Object value) {
        try {return json.writeValueAsString(value);}
        catch(JsonProcessingException failure){throw new IllegalStateException("MONTHLY_TARGET_SNAPSHOT_INVALID",failure);}
    }
}
