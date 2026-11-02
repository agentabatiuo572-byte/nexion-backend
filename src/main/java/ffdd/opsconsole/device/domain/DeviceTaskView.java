package ffdd.opsconsole.device.domain;

import java.math.BigDecimal;
import java.time.LocalDateTime;

public record DeviceTaskView(
        String taskId,
        String name,
        BigDecimal price,
        String unit,
        String requirement,
        BigDecimal saturation,
        String status,
        String taskClass,
        String model,
        BigDecimal minReward,
        BigDecimal maxReward,
        String minVram,
        String killInit,
        LocalDateTime createdAt,
        LocalDateTime updatedAt,
        Integer selectionWeight,
        Integer expectedDurationMinSec,
        Integer expectedDurationMaxSec,
        Integer executionTimeoutSec,
        String runtimeKey,
        String validatorKey,
        String workloadConfigJson, ffdd.opsconsole.device.application.SimulatedTaskPolicyService.TypeView simulation) {
    public DeviceTaskView(
        String taskId,
        String name,
        BigDecimal price,
        String unit,
        String requirement,
        BigDecimal saturation,
        String status,
        String taskClass,
        String model,
        BigDecimal minReward,
        BigDecimal maxReward,
        String minVram,
        String killInit,
        LocalDateTime createdAt,
        LocalDateTime updatedAt,
        Integer selectionWeight,
        Integer expectedDurationMinSec,
        Integer expectedDurationMaxSec,
        Integer executionTimeoutSec,
        String runtimeKey,
        String validatorKey,
        String workloadConfigJson) {
        this(taskId,name,price,unit,requirement,saturation,status,taskClass,model,minReward,maxReward,minVram,killInit,createdAt,updatedAt,selectionWeight,expectedDurationMinSec,expectedDurationMaxSec,executionTimeoutSec,runtimeKey,validatorKey,workloadConfigJson,null);
    }

    @org.apache.ibatis.annotations.AutomapConstructor
    public DeviceTaskView(String taskId, String name, BigDecimal price, String unit, String requirement,
            BigDecimal saturation, String status, String taskClass, String model, BigDecimal minReward,
            BigDecimal maxReward, String minVram, String killInit, LocalDateTime createdAt, LocalDateTime updatedAt) {
        this(taskId, name, price, unit, requirement, saturation, status, taskClass, model, minReward, maxReward,
                minVram, killInit, createdAt, updatedAt, 1,
                TaskSchedulingSettings.defaultDuration(taskClass),
                TaskSchedulingSettings.defaultDuration(taskClass),
                Math.max(300, TaskSchedulingSettings.defaultDuration(taskClass)),
                "EM".equalsIgnoreCase(taskClass) && "BGE-M3".equalsIgnoreCase(model) ? "test-bge-m3-v1" : "",
                "EM".equalsIgnoreCase(taskClass) && "BGE-M3".equalsIgnoreCase(model) ? "test-bge-m3-v1" : "", "{}");
    }

    public DeviceTaskView withScheduling(TaskSchedulingSettings settings) {
        return new DeviceTaskView(taskId, name, price, unit, requirement, saturation, status, taskClass, model,
                minReward, maxReward, minVram, killInit, createdAt, updatedAt, settings.selectionWeight(),
                settings.expectedDurationMinSec(), settings.expectedDurationMaxSec(), settings.executionTimeoutSec(),
                settings.runtimeKey(), settings.validatorKey(), settings.workloadConfigJson());
    }

    public TaskSchedulingSettings scheduling() {
        return new TaskSchedulingSettings(selectionWeight, expectedDurationMinSec,
                expectedDurationMaxSec, executionTimeoutSec, runtimeKey, validatorKey, workloadConfigJson);
    }

    public DeviceTaskView withSimulation(ffdd.opsconsole.device.application.SimulatedTaskPolicyService.TypeView value) { return new DeviceTaskView(taskId,name,price,unit,requirement,saturation,status,taskClass,model,minReward,maxReward,minVram,killInit,createdAt,updatedAt,selectionWeight,expectedDurationMinSec,expectedDurationMaxSec,executionTimeoutSec,runtimeKey,validatorKey,workloadConfigJson,value); }
}
