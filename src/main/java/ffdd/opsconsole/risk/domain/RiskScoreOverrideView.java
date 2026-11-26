package ffdd.opsconsole.risk.domain;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import org.apache.ibatis.annotations.AutomapConstructor;

public record RiskScoreOverrideView(
        String userNo,
        Integer modelScore,
        Integer overrideScore,
        String reason,
        String operator,
        String timeText,
        Boolean active,
        SystemScoreState systemScoreState
) {
    @AutomapConstructor
    public RiskScoreOverrideView {}

    public RiskScoreOverrideView(String userNo, Integer modelScore, Integer overrideScore,
                                 String reason, String operator, String timeText, Boolean active) {
        this(userNo, modelScore, overrideScore, reason, operator, timeText, active,
                modelScore != null && modelScore != 0 ? SystemScoreState.SCORED : SystemScoreState.UNKNOWN);
    }

    @JsonProperty("systemScoreBefore")
    @JsonInclude(JsonInclude.Include.ALWAYS)
    public Integer systemScoreBefore() {
        return systemScoreState == SystemScoreState.SCORED ? modelScore : null;
    }

    public enum SystemScoreState { SCORED, UNSCORED, UNKNOWN }
}
