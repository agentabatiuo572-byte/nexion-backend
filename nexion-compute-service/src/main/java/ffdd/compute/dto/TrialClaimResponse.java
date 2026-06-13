package ffdd.compute.dto;

import ffdd.compute.domain.TrialClaim;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import lombok.Data;

@Data
public class TrialClaimResponse {
    private Long id;
    private Long userId;
    private String claimNo;
    private String clientRequestNo;
    private String status;
    private Long userDeviceId;
    private String deviceName;
    private Integer durationDays;
    private BigDecimal dailyUsdt;
    private BigDecimal dailyNex;
    private Integer seatsLeftToday;
    private BigDecimal offsetCapUsdt;
    private BigDecimal priceUsdt;
    private LocalDateTime claimedAt;
    private LocalDateTime expiresAt;
    private String quotaSnapshot;

    public static TrialClaimResponse from(TrialClaim claim) {
        if (claim == null) {
            return null;
        }
        TrialClaimResponse response = new TrialClaimResponse();
        response.setId(claim.getId());
        response.setUserId(claim.getUserId());
        response.setClaimNo(claim.getClaimNo());
        response.setClientRequestNo(claim.getClientRequestNo());
        response.setStatus(claim.getStatus());
        response.setUserDeviceId(claim.getUserDeviceId());
        response.setDeviceName(claim.getDeviceName());
        response.setDurationDays(claim.getDurationDays());
        response.setDailyUsdt(claim.getDailyUsdt());
        response.setDailyNex(claim.getDailyNex());
        response.setSeatsLeftToday(claim.getSeatsLeftToday());
        response.setOffsetCapUsdt(claim.getOffsetCapUsdt());
        response.setPriceUsdt(claim.getPriceUsdt());
        response.setClaimedAt(claim.getClaimedAt());
        response.setExpiresAt(claim.getExpiresAt());
        response.setQuotaSnapshot(claim.getQuotaSnapshot());
        return response;
    }
}
