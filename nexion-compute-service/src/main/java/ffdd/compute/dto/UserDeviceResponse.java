package ffdd.compute.dto;

import ffdd.compute.domain.UserDevice;
import java.math.BigDecimal;
import java.time.Duration;
import java.time.LocalDateTime;
import lombok.Data;

@Data
public class UserDeviceResponse {
    private Long id;
    private Long userId;
    private String sourceOrderNo;
    private Long productId;
    private String productCode;
    private String productTier;
    private String instanceNo;
    private String name;
    private String deviceType;
    private Integer generation;
    private String gpuModel;
    private Integer vramTotalGb;
    private BigDecimal basePowerW;
    private String dcLocation;
    private BigDecimal priceUsdtSnapshot;
    private String ownershipStatus;
    private String sourceChannel;
    private String status;
    private BigDecimal hashrate;
    private BigDecimal dailyUsdt;
    private BigDecimal dailyNex;
    private LocalDateTime lastSeenAt;
    private LocalDateTime purchasedAt;
    private LocalDateTime activatedAt;
    private LocalDateTime deactivatedAt;
    private Integer pendingDeactivate;
    private Integer monthsOwned;
    private BigDecimal currentEfficiency;
    private BigDecimal effectiveDailyUsdt;
    private BigDecimal effectiveDailyNex;
    private String runtimeStatus;
    private String runtimeRegion;
    private String runtimeCountry;
    private String runtimeCity;
    private BigDecimal runtimeTemperatureC;
    private BigDecimal runtimePowerW;
    private BigDecimal runtimeGpuUsage;
    private BigDecimal runtimeVramUsedGb;
    private Integer runtimeBatteryLevel;
    private Boolean runtimeCharging;
    private Boolean runtimeNetworkReachable;
    private String runtimeThermalState;
    private String runtimePausedReason;
    private String runtimeActiveTaskNo;
    private String runtimeClientName;
    private LocalDateTime runtimeReportedAt;
    private String runtimeCacheStatus;
    private String currentTaskNo;
    private String currentTaskType;
    private String currentTaskClientName;
    private String currentTaskStatus;
    private LocalDateTime currentTaskStartedAt;
    private LocalDateTime currentTaskLeaseExpiresAt;
    private Integer currentTaskProgressPct;

    public static UserDeviceResponse from(UserDevice device, DeviceLifecycleResponse lifecycle) {
        UserDeviceResponse response = new UserDeviceResponse();
        response.setId(device.getId());
        response.setUserId(device.getUserId());
        response.setSourceOrderNo(device.getSourceOrderNo());
        response.setProductId(device.getProductId());
        response.setProductCode(device.getProductCode());
        response.setProductTier(device.getProductTier());
        response.setInstanceNo(device.getInstanceNo());
        response.setName(device.getName());
        response.setDeviceType(device.getDeviceType());
        response.setGeneration(device.getGeneration());
        response.setGpuModel(device.getGpuModel());
        response.setVramTotalGb(device.getVramTotalGb());
        response.setBasePowerW(device.getBasePowerW());
        response.setDcLocation(device.getDcLocation());
        response.setPriceUsdtSnapshot(device.getPriceUsdtSnapshot());
        response.setOwnershipStatus(device.getOwnershipStatus());
        response.setSourceChannel(device.getSourceChannel());
        response.setStatus(device.getStatus());
        response.setHashrate(device.getHashrate());
        response.setDailyUsdt(device.getDailyUsdt());
        response.setDailyNex(device.getDailyNex());
        response.setLastSeenAt(device.getLastSeenAt());
        response.setPurchasedAt(device.getPurchasedAt());
        response.setActivatedAt(device.getActivatedAt());
        response.setDeactivatedAt(device.getDeactivatedAt());
        response.setPendingDeactivate(device.getPendingDeactivate());
        response.setMonthsOwned(lifecycle.getMonthsOwned());
        response.setCurrentEfficiency(lifecycle.getCurrentEfficiency());
        response.setEffectiveDailyUsdt(lifecycle.getEffectiveDailyUsdt());
        response.setEffectiveDailyNex(lifecycle.getEffectiveDailyNex());
        return response;
    }

    public static UserDeviceResponse from(
            UserDevice device,
            DeviceLifecycleResponse lifecycle,
            DeviceStatusResponse runtime,
            ffdd.compute.domain.ComputeTask currentTask) {
        UserDeviceResponse response = from(device, lifecycle);
        if (runtime != null) {
            response.setRuntimeStatus(runtime.getStatus());
            response.setRuntimeRegion(runtime.getRegion());
            response.setRuntimeCountry(runtime.getCountry());
            response.setRuntimeCity(runtime.getCity());
            response.setRuntimeTemperatureC(runtime.getTemperatureC());
            response.setRuntimePowerW(runtime.getPowerW());
            response.setRuntimeGpuUsage(runtime.getGpuUsage());
            response.setRuntimeVramUsedGb(runtime.getVramUsedGb());
            response.setRuntimeBatteryLevel(runtime.getBatteryLevel());
            response.setRuntimeCharging(runtime.getIsCharging());
            response.setRuntimeNetworkReachable(runtime.getNetworkReachable());
            response.setRuntimeThermalState(runtime.getThermalState());
            response.setRuntimePausedReason(runtime.getPausedReason());
            response.setRuntimeActiveTaskNo(runtime.getActiveTaskNo());
            response.setRuntimeClientName(runtime.getClientName());
            response.setRuntimeReportedAt(runtime.getReportedAt());
            response.setRuntimeCacheStatus(runtime.getCacheStatus());
        }
        if (currentTask != null) {
            response.setCurrentTaskNo(currentTask.getTaskNo());
            response.setCurrentTaskType(currentTask.getTaskType());
            response.setCurrentTaskClientName(currentTask.getClientName());
            response.setCurrentTaskStatus(currentTask.getStatus());
            response.setCurrentTaskStartedAt(currentTask.getStartedAt());
            response.setCurrentTaskLeaseExpiresAt(currentTask.getLeaseExpiresAt());
            response.setCurrentTaskProgressPct(progressPct(currentTask));
        }
        return response;
    }

    private static int progressPct(ffdd.compute.domain.ComputeTask task) {
        if ("COMPLETED".equals(task.getStatus())) {
            return 100;
        }
        if (task.getStartedAt() == null || task.getLeaseExpiresAt() == null) {
            return 0;
        }
        long totalSeconds = Math.max(1, Duration.between(task.getStartedAt(), task.getLeaseExpiresAt()).toSeconds());
        long elapsedSeconds = Math.max(0, Duration.between(task.getStartedAt(), LocalDateTime.now()).toSeconds());
        return Math.max(0, Math.min(99, (int) ((elapsedSeconds * 100) / totalSeconds)));
    }
}
