package ffdd.opsconsole.risk.dto;

public record RiskRuleOverviewQueryRequest(
        Integer rulePageNum,
        Integer rulePageSize,
        Integer hitPageNum,
        Integer hitPageSize,
        String hitAction
) {
}
