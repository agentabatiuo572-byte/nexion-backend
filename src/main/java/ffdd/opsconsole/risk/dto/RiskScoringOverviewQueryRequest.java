package ffdd.opsconsole.risk.dto;

public record RiskScoringOverviewQueryRequest(
        Integer overridePageNum,
        Integer overridePageSize
) {
}
