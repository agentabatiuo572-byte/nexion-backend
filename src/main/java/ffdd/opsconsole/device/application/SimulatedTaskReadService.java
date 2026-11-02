package ffdd.opsconsole.device.application;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import ffdd.opsconsole.device.dto.AppTaskAssignmentView;
import ffdd.opsconsole.device.mapper.SimulatedTaskMapper;
import ffdd.opsconsole.device.mapper.SimulatedTaskMapper.TaskRow;
import ffdd.opsconsole.shared.config.DateTimeFormatConfig;
import ffdd.opsconsole.shared.exception.BizException;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.*;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Account snapshot only: no read starts a task, renews a phone or settles money. */
@Service
@RequiredArgsConstructor
public class SimulatedTaskReadService {
    private final AppTaskAssignmentService legacy;
    private final SimulatedTaskService simulated;
    private final SimulatedTaskMapper mapper;
    private final ObjectMapper json;
    private final Clock clock;
    private final ComputeV2ReadService legacyV2;
    private final org.springframework.core.env.Environment environment;

    @Transactional(readOnly=true)
    public Map<String,Object> assignments(long userId) {
        var response=legacy.assignments(userId);
        if(response.getCode()!=0 || response.getData()==null)throw new BizException(response.getCode(),response.getMessage());
        var original=response.getData();
        var current=simulated.currentTasksByUser(userId);
        var runtimes=simulated.runtimesByUser(userId);
        var ids=original.devices().stream().map(device->device.deviceId()).toList();
        var histories=ids.isEmpty()?List.<TaskRow>of():mapper.recentTasksByUser(userId,ids,10);
        Map<String,Object> oldV2=null;
        if(environment.getProperty("nexion.compute-v2.enabled",Boolean.class,false)) {
            try {oldV2=legacyV2.assignments(userId);}
            catch(BizException unavailable) {if(unavailable.getCode()!=404)throw unavailable;}
        }
        var oldDevices=new HashMap<Long,Map<String,Object>>();
        if(oldV2!=null)for(var device:json.convertValue(oldV2.get("devices"),new TypeReference<List<LinkedHashMap<String,Object>>>(){}))
            oldDevices.put(((Number)device.get("deviceId")).longValue(),device);
        var devices=new ArrayList<Map<String,Object>>();
        for(var device:original.devices()) {
            var row=oldDevices.containsKey(device.deviceId())?new LinkedHashMap<>(oldDevices.get(device.deviceId()))
                    :json.convertValue(device,new TypeReference<LinkedHashMap<String,Object>>(){});
            var task=current.get(device.deviceId());
            if(task!=null) {
                if(row.get("currentTask") instanceof Map<?,?> old && !task.taskNo().equals(old.get("taskNo")))
                    throw new BizException(503,"SIMULATED_TASK_LEGACY_NOT_DRAINED");
                row.put("currentTask",task(task));
            }
            var history=new LinkedHashMap<String,Map<String,Object>>();
            for(var old:json.convertValue(row.get("recentTasks"),new TypeReference<List<LinkedHashMap<String,Object>>>(){}))
                if(!((String)old.get("taskNo")).startsWith("SIM-"))history.put((String)old.get("taskNo"),old);
            for(var done:histories)if(done.deviceId()==device.deviceId())history.put(done.taskNo(),task(done));
            row.put("recentTasks",history.values().stream().sorted(Comparator.comparingLong((Map<String,Object> value)->
                    completedAt(value.get("completedAt"))).reversed()).limit(10).toList());
            var runtime=runtimes.get(device.deviceId());
            row.put("phoneValidUntil",runtime==null?null:runtime.phoneValidUntil());
            row.put("simulationState",runtime==null?row.get("currentTask")==null?"WAITING_CONFIG":"CLOSING":runtime.state());
            devices.add(row);
        }
        return fields("protocolVersion",3,"serverNow",LocalDateTime.now(clock),"devices",devices,"pendingEarned",oldV2==null?List.of():oldV2.get("pendingEarned"),
                "source","server","sourceEnvironment","PRODUCTION","runId","","serverCanonical",true);
    }

    private Map<String,Object> task(TaskRow task) {
        boolean completed="COMPLETED".equals(task.status());
        String asset=task.asset();
        BigDecimal usdt="USDT".equals(task.asset())?task.rewardAmount():BigDecimal.ZERO;
        LocalDateTime end=completed?task.dueAt():LocalDateTime.now(clock);
        return fields("protocolVersion",3,"taskNo",task.taskNo(),"deviceId",task.deviceId(),"taskId",task.taskTypeId(),
                "taskName",task.taskName(),"taskClass",task.taskClass(),"model","","client","UVEL","status",task.status(),
                "rewardUsdt",usdt,"rewardAmount",task.rewardAmount().toPlainString(),"rewardAsset",asset,
                "requiredSeconds",task.durationSeconds(),"startedAt",task.startedAt(),"queuedAt",task.startedAt(),
                "completableAt",task.dueAt(),"completedAt",task.completedAt(),"actualDurationSec",Math.max(0,Duration.between(task.startedAt(),end).toSeconds()),
                "receiptNo",completed?task.receiptNo():null,"settlementStatus",completed?"POSTED":"NONE","settledAt",task.postedAt(),
                "settlementBatchNo",null,"configVersion",Long.toString(task.taskTypeVersion()),"proofNonce",null,
                "proofExpiresAt",null,"pausedAt",null,"pausedSeconds",0,"executionMode","SIMULATED","proofKind","TASK_RECEIPT", "modelExecuted",false,
                "source","server","sourceEnvironment","PRODUCTION","runId","","serverCanonical",true);
    }
    private static LinkedHashMap<String,Object> fields(Object... values) {
        var map=new LinkedHashMap<String,Object>();
        for(int i=0;i<values.length;i+=2)map.put((String)values[i],values[i+1]);
        return map;
    }
    private static long completedAt(Object value) {
        if(value==null)return 0;
        if(value instanceof java.time.Instant instant)return instant.toEpochMilli();
        if(value instanceof LocalDateTime time)return time.atZone(DateTimeFormatConfig.BUSINESS_ZONE).toInstant().toEpochMilli();
        String text=(String)value;
        if(text.endsWith("Z"))return java.time.Instant.parse(text).toEpochMilli();
        LocalDateTime time=text.contains("T")?LocalDateTime.parse(text):LocalDateTime.parse(text,DateTimeFormatConfig.DATE_TIME_FORMATTER);
        return time.atZone(DateTimeFormatConfig.BUSINESS_ZONE).toInstant().toEpochMilli();
    }
}
