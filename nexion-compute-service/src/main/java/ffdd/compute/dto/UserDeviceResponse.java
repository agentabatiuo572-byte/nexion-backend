package ffdd.compute.dto;

import ffdd.compute.domain.UserDevice;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import lombok.Data;

@Data
public class UserDeviceResponse {
    private Long id;
    private Long userId;
    private String sourceOrderNo;
    private Long productId;
    private String productTier;
    private String instanceNo;
    private String name;
    private String deviceType;
    private String status;
    private BigDecimal hashrate;
    private BigDecimal dailyUsdt;
    private BigDecimal dailyNex;
    private LocalDateTime lastSeenAt;
    private LocalDateTime purchasedAt;
    private LocalDateTime activatedAt;
    private Integer pendingDeactivate;
    private Integer monthsOwned;
    private BigDecimal currentEfficiency;
    private BigDecimal effectiveDailyUsdt;
    private BigDecimal effectiveDailyNex;

    public static UserDeviceResponse from(UserDevice device, DeviceLifecycleResponse lifecycle) {
        UserDeviceResponse response = new UserDeviceResponse();
        response.setId(device.getId());
        response.setUserId(device.getUserId());
        response.setSourceOrderNo(device.getSourceOrderNo());
        response.setProductId(device.getProductId());
        response.setProductTier(device.getProductTier());
        response.setInstanceNo(device.getInstanceNo());
        response.setName(device.getName());
        response.setDeviceType(device.getDeviceType());
        response.setStatus(device.getStatus());
        response.setHashrate(device.getHashrate());
        response.setDailyUsdt(device.getDailyUsdt());
        response.setDailyNex(device.getDailyNex());
        response.setLastSeenAt(device.getLastSeenAt());
        response.setPurchasedAt(device.getPurchasedAt());
        response.setActivatedAt(device.getActivatedAt());
        response.setPendingDeactivate(device.getPendingDeactivate());
        response.setMonthsOwned(lifecycle.getMonthsOwned());
        response.setCurrentEfficiency(lifecycle.getCurrentEfficiency());
        response.setEffectiveDailyUsdt(lifecycle.getEffectiveDailyUsdt());
        response.setEffectiveDailyNex(lifecycle.getEffectiveDailyNex());
        return response;
    }
}
