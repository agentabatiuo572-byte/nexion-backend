package ffdd.team.dto;

import java.math.BigDecimal;

public class TeamHardwareQuotaUpdateRequest {
    private String productNo;
    private String displayName;
    private String note;
    private Integer directRefs;
    private BigDecimal monthVolumeUsd;
    private Integer monthlyQuota;
    private String unlockMode;
    private Integer status;
    private Integer sortOrder;

    public String getProductNo() { return productNo; }
    public void setProductNo(String productNo) { this.productNo = productNo; }
    public String getDisplayName() { return displayName; }
    public void setDisplayName(String displayName) { this.displayName = displayName; }
    public String getNote() { return note; }
    public void setNote(String note) { this.note = note; }
    public Integer getDirectRefs() { return directRefs; }
    public void setDirectRefs(Integer directRefs) { this.directRefs = directRefs; }
    public BigDecimal getMonthVolumeUsd() { return monthVolumeUsd; }
    public void setMonthVolumeUsd(BigDecimal monthVolumeUsd) { this.monthVolumeUsd = monthVolumeUsd; }
    public Integer getMonthlyQuota() { return monthlyQuota; }
    public void setMonthlyQuota(Integer monthlyQuota) { this.monthlyQuota = monthlyQuota; }
    public String getUnlockMode() { return unlockMode; }
    public void setUnlockMode(String unlockMode) { this.unlockMode = unlockMode; }
    public Integer getStatus() { return status; }
    public void setStatus(Integer status) { this.status = status; }
    public Integer getSortOrder() { return sortOrder; }
    public void setSortOrder(Integer sortOrder) { this.sortOrder = sortOrder; }
}
