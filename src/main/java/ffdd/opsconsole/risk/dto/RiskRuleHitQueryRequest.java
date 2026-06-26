package ffdd.opsconsole.risk.dto;

public record RiskRuleHitQueryRequest(
        String action,
        Integer limit,
        Integer pageNum,
        Integer pageSize
) {
}
