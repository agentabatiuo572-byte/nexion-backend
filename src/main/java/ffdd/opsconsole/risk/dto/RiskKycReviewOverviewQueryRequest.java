package ffdd.opsconsole.risk.dto;

public record RiskKycReviewOverviewQueryRequest(
        Integer ticketPageNum,
        Integer ticketPageSize,
        String ticketFilter
) {
}
