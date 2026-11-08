package ffdd.opsconsole.device.application;

import ffdd.opsconsole.device.domain.DeviceOpsRepository;
import ffdd.opsconsole.device.domain.DeviceOpsView;
import ffdd.opsconsole.device.mapper.DeviceMonthlyTargetMapper;
import ffdd.opsconsole.shared.capacity.E3DeviceCapacityPolicy;
import ffdd.opsconsole.shared.config.DateTimeFormatConfig;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Clock;
import java.time.LocalDateTime;
import java.util.*;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;

/** Batch-only current projections. This reader never reserves, generates weights, or writes a cycle. */
@Service
@RequiredArgsConstructor
public class DeviceMonthlyTargetReadService {
    private final DeviceMonthlyTargetMapper mapper;
    private final ObjectProvider<DeviceOpsRepository> devices;
    private final Clock clock;
    public record Projection(BigDecimal daily,BigDecimal target,BigDecimal band,BigDecimal capacity,
            DeviceMonthlyTargetPacing.Cycle cycle,DeviceMonthlyTargetMapper.Current source) { }
    public Map<Long,Projection> projections(List<Long> ids) {
        if(ids.isEmpty())return Map.of();
        var rows=mapper.current(ids);var config=devices.getObject().e3Config();
        var now=LocalDateTime.ofInstant(clock.instant(),DateTimeFormatConfig.BUSINESS_ZONE);
        var result=new HashMap<Long,Projection>();
        for(var row:rows) {
            var anchor=row.purchasedAt()!=null?row.purchasedAt():row.activatedAt();
            var cycle=anchor==null?null:DeviceMonthlyTargetPacing.cycleAt(anchor,now);
            BigDecimal capacity=BigDecimal.valueOf(100),daily=row.dailyUsdt();
            if(anchor!=null && E3DeviceCapacityPolicy.classify(row.productCode(),row.deviceType())!=null)
                capacity=E3DeviceCapacityPolicy.project(row.productCode(),row.deviceType(),row.purchasedAt(),row.activatedAt(),now,config).capacityPct();
            if(row.monthlyTargetUsdt()!=null) {
                daily=row.monthlyTargetUsdt().multiply(capacity.movePointLeft(2)).divide(BigDecimal.valueOf(30),6,RoundingMode.HALF_UP);
            }
            result.put(row.deviceId(),new Projection(daily,row.monthlyTargetUsdt(),row.monthlyTargetBandPct(),capacity,cycle,row));
        }
        return result;
    }
    public List<DeviceOpsView> enrich(List<DeviceOpsView> rows) {
        var projections=projections(rows.stream().map(DeviceOpsView::id).toList());
        var keys=projections.values().stream().filter(p->p.cycle()!=null)
                .map(p->new DeviceMonthlyTargetMapper.Key(p.source().deviceId(),p.cycle().start())).toList();
        var cycles=new HashMap<Long,DeviceMonthlyTargetMapper.Row>();
        if(!keys.isEmpty())for(var row:mapper.batch(keys))cycles.put(row.deviceId(),row);
        long e3=mapper.currentE3Version();
        return rows.stream().map(device->{
            var p=projections.get(device.id());if(p==null || p.cycle()==null)return device;
            var old=cycles.get(device.id());boolean active=p.target()!=null;
            if(old==null && !active)return device;
            if(old!=null && (old.userId()!=device.userId() || !old.instanceNo().equals(device.instanceNo())))
                throw new IllegalStateException("MONTHLY_TARGET_CYCLE_IDENTITY_CONFLICT");
            BigDecimal q=active?p.target().multiply(p.capacity().movePointLeft(2)):null;
            if(q!=null && q.compareTo(new BigDecimal("0.01"))<0)q=BigDecimal.ZERO;
            boolean changed=old!=null && (old.configChanged() || !active || old.firstMonthlyTargetUsdt().compareTo(p.target())!=0
                    ||old.firstMonthlyTargetBandPct().compareTo(p.band())!=0||old.firstCapacityPct().compareTo(p.capacity())!=0||old.firstE3Version()!=e3);
            changed|=p.source().targetEffectiveAt()!=null && p.source().targetEffectiveAt().isAfter(p.cycle().start());
            var target=new DeviceOpsView.MonthlyTarget(p.cycle().index()+1,p.cycle().start(),p.cycle().end(),
                    old==null?BigDecimal.ZERO.setScale(6):old.creditedUsdt(),q==null?null:q.multiply(BigDecimal.ONE.subtract(p.band().movePointLeft(2))),
                    q==null?null:q.multiply(BigDecimal.ONE.add(p.band().movePointLeft(2))),p.capacity(),active,changed);
            return device.withMonthlyTarget(target,active?p.daily():device.dailyUsdt(),active?p.target().divide(BigDecimal.valueOf(30),6,RoundingMode.HALF_UP).toPlainString():device.baseRate());
        }).toList();
    }
}
