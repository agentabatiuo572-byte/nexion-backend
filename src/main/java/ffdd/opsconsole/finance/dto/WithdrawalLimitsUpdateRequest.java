package ffdd.opsconsole.finance.dto;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonSetter;
import java.math.BigDecimal;
import java.util.LinkedHashSet;
import java.util.Set;

/** Canonical D5 aggregate update. Presence is tracked so explicit null Phase fields cannot bypass the read-only guard. */
public class WithdrawalLimitsUpdateRequest {
    private final Set<String> presentFields = new LinkedHashSet<>();
    private Long expectedVersion;
    private Integer dailyLimitCount;
    private BigDecimal balanceMaxRatio;
    private BigDecimal networkFeeRatio;
    private BigDecimal networkFeeMin;
    private BigDecimal networkFeeMax;
    private BigDecimal nexFeeOffsetRate;
    private Integer cooldownDays;
    private BigDecimal penaltyFeeRate;
    private Boolean complianceHoldEnabled;
    private String reason;
    private String operator;

    public Long getExpectedVersion() { return expectedVersion; }
    @JsonSetter public void setExpectedVersion(Long value) { expectedVersion = value; presentFields.add("expectedVersion"); }
    public Integer getDailyLimitCount() { return dailyLimitCount; }
    @JsonSetter public void setDailyLimitCount(Integer value) { dailyLimitCount = value; presentFields.add("dailyLimitCount"); }
    public BigDecimal getBalanceMaxRatio() { return balanceMaxRatio; }
    @JsonSetter public void setBalanceMaxRatio(BigDecimal value) { balanceMaxRatio = value; presentFields.add("balanceMaxRatio"); }
    public BigDecimal getNetworkFeeRatio() { return networkFeeRatio; }
    @JsonSetter public void setNetworkFeeRatio(BigDecimal value) { networkFeeRatio = value; presentFields.add("networkFeeRatio"); }
    public BigDecimal getNetworkFeeMin() { return networkFeeMin; }
    @JsonSetter public void setNetworkFeeMin(BigDecimal value) { networkFeeMin = value; presentFields.add("networkFeeMin"); }
    public BigDecimal getNetworkFeeMax() { return networkFeeMax; }
    @JsonSetter public void setNetworkFeeMax(BigDecimal value) { networkFeeMax = value; presentFields.add("networkFeeMax"); }
    public BigDecimal getNexFeeOffsetRate() { return nexFeeOffsetRate; }
    @JsonSetter public void setNexFeeOffsetRate(BigDecimal value) { nexFeeOffsetRate = value; presentFields.add("nexFeeOffsetRate"); }
    public Integer getCooldownDays() { return cooldownDays; }
    @JsonSetter public void setCooldownDays(Integer value) { cooldownDays = value; presentFields.add("cooldownDays"); }
    public BigDecimal getPenaltyFeeRate() { return penaltyFeeRate; }
    @JsonSetter public void setPenaltyFeeRate(BigDecimal value) { penaltyFeeRate = value; presentFields.add("penaltyFeeRate"); }
    public Boolean getComplianceHoldEnabled() { return complianceHoldEnabled; }
    @JsonSetter public void setComplianceHoldEnabled(Boolean value) { complianceHoldEnabled = value; presentFields.add("complianceHoldEnabled"); }
    public String getReason() { return reason; }
    @JsonSetter public void setReason(String value) { reason = value; presentFields.add("reason"); }
    public String getOperator() { return operator; }
    @JsonSetter public void setOperator(String value) { operator = value; presentFields.add("operator"); }

    @JsonIgnore
    public Set<String> getPresentFields() {
        return Set.copyOf(presentFields);
    }

    @JsonIgnore
    public boolean hasPhaseFields() {
        return presentFields.stream().anyMatch(Set.of(
                "cooldownDays", "penaltyFeeRate", "complianceHoldEnabled")::contains);
    }

    @JsonIgnore
    public Set<String> changedD5Fields() {
        Set<String> fields = new LinkedHashSet<>(presentFields);
        fields.retainAll(Set.of(
                "dailyLimitCount", "balanceMaxRatio", "networkFeeRatio",
                "networkFeeMin", "networkFeeMax", "nexFeeOffsetRate"));
        return fields;
    }
}
