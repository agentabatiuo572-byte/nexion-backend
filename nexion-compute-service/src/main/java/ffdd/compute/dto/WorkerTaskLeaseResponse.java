package ffdd.compute.dto;

import ffdd.compute.domain.ComputeTask;
import ffdd.compute.domain.UserDevice;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import lombok.Data;

@Data
public class WorkerTaskLeaseResponse {
    private String taskNo;
    private Long userId;
    private Long userDeviceId;
    private String taskType;
    private String clientName;
    private String status;
    private LocalDateTime startedAt;
    private LocalDateTime workerAckAt;
    private LocalDateTime leaseExpiresAt;
    private Integer attemptCount;
    private Integer maxAttempts;
    private String deviceInstanceNo;
    private String deviceName;
    private String deviceType;
    private String deviceStatus;
    private BigDecimal hashrate;
    private BigDecimal dailyUsdt;
    private BigDecimal dailyNex;
    private LocalDateTime deviceLastSeenAt;

    public static WorkerTaskLeaseResponse from(ComputeTask task, UserDevice device) {
        WorkerTaskLeaseResponse response = new WorkerTaskLeaseResponse();
        response.setTaskNo(task.getTaskNo());
        response.setUserId(task.getUserId());
        response.setUserDeviceId(task.getUserDeviceId());
        response.setTaskType(task.getTaskType());
        response.setClientName(task.getClientName());
        response.setStatus(task.getStatus());
        response.setStartedAt(task.getStartedAt());
        response.setWorkerAckAt(task.getWorkerAckAt());
        response.setLeaseExpiresAt(task.getLeaseExpiresAt());
        response.setAttemptCount(task.getAttemptCount());
        response.setMaxAttempts(task.getMaxAttempts());
        response.setDeviceInstanceNo(device.getInstanceNo());
        response.setDeviceName(device.getName());
        response.setDeviceType(device.getDeviceType());
        response.setDeviceStatus(device.getStatus());
        response.setHashrate(device.getHashrate());
        response.setDailyUsdt(device.getDailyUsdt());
        response.setDailyNex(device.getDailyNex());
        response.setDeviceLastSeenAt(device.getLastSeenAt());
        return response;
    }
}
