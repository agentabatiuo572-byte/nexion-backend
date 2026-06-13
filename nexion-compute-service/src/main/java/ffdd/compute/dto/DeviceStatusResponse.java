package ffdd.compute.dto;

import ffdd.compute.domain.UserDevice;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import lombok.Data;

@Data
public class DeviceStatusResponse {
    private Long userDeviceId;
    private Long userId;
    private String instanceNo;
    private String name;
    private String deviceType;
    private String productCode;
    private Integer generation;
    private String status;
    private BigDecimal hashrate;
    private BigDecimal dailyUsdt;
    private BigDecimal dailyNex;
    private String region;
    private String country;
    private String city;
    private BigDecimal latitude;
    private BigDecimal longitude;
    private BigDecimal temperatureC;
    private BigDecimal powerW;
    private BigDecimal gpuUsage;
    private BigDecimal vramUsedGb;
    private Integer batteryLevel;
    private Boolean isCharging;
    private Boolean networkReachable;
    private String thermalState;
    private String pausedReason;
    private String activeTaskNo;
    private String clientName;
    private String agentVersion;
    private LocalDateTime reportedAt;
    private LocalDateTime lastSeenAt;
    private String cacheStatus;

    public static DeviceStatusResponse fromDevice(UserDevice device, String cacheStatus) {
        DeviceStatusResponse response = new DeviceStatusResponse();
        response.setUserDeviceId(device.getId());
        response.setUserId(device.getUserId());
        response.setInstanceNo(device.getInstanceNo());
        response.setName(device.getName());
        response.setDeviceType(device.getDeviceType());
        response.setProductCode(device.getProductCode());
        response.setGeneration(device.getGeneration());
        response.setStatus(device.getStatus());
        response.setHashrate(device.getHashrate());
        response.setDailyUsdt(device.getDailyUsdt());
        response.setDailyNex(device.getDailyNex());
        response.setLastSeenAt(device.getLastSeenAt());
        response.setCacheStatus(cacheStatus);
        return response;
    }
}
