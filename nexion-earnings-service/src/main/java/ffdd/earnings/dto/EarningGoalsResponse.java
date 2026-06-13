package ffdd.earnings.dto;

import java.math.BigDecimal;
import java.util.List;

public class EarningGoalsResponse {
    private Long userId;
    private BigDecimal lifetimeUsdt;
    private List<EarningGoalResponse> goals;

    public EarningGoalsResponse(Long userId, BigDecimal lifetimeUsdt, List<EarningGoalResponse> goals) {
        this.userId = userId;
        this.lifetimeUsdt = lifetimeUsdt;
        this.goals = goals;
    }

    public Long getUserId() {
        return userId;
    }

    public void setUserId(Long userId) {
        this.userId = userId;
    }

    public BigDecimal getLifetimeUsdt() {
        return lifetimeUsdt;
    }

    public void setLifetimeUsdt(BigDecimal lifetimeUsdt) {
        this.lifetimeUsdt = lifetimeUsdt;
    }

    public List<EarningGoalResponse> getGoals() {
        return goals;
    }

    public void setGoals(List<EarningGoalResponse> goals) {
        this.goals = goals;
    }
}
