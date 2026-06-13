package ffdd.team.dto;

import java.math.BigDecimal;

public class CommissionRuleUpdateRequest {
    private BigDecimal usdtRate;
    private BigDecimal nexPerUsd;
    private BigDecimal fixedNex;
    private BigDecimal dailyCapUsdt;
    private Integer cooldownDays;
    private Integer status;

    public BigDecimal getUsdtRate() {
        return usdtRate;
    }

    public void setUsdtRate(BigDecimal usdtRate) {
        this.usdtRate = usdtRate;
    }

    public BigDecimal getNexPerUsd() {
        return nexPerUsd;
    }

    public void setNexPerUsd(BigDecimal nexPerUsd) {
        this.nexPerUsd = nexPerUsd;
    }

    public BigDecimal getFixedNex() {
        return fixedNex;
    }

    public void setFixedNex(BigDecimal fixedNex) {
        this.fixedNex = fixedNex;
    }

    public BigDecimal getDailyCapUsdt() {
        return dailyCapUsdt;
    }

    public void setDailyCapUsdt(BigDecimal dailyCapUsdt) {
        this.dailyCapUsdt = dailyCapUsdt;
    }

    public Integer getCooldownDays() {
        return cooldownDays;
    }

    public void setCooldownDays(Integer cooldownDays) {
        this.cooldownDays = cooldownDays;
    }

    public Integer getStatus() {
        return status;
    }

    public void setStatus(Integer status) {
        this.status = status;
    }
}
