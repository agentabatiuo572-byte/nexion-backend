package ffdd.opsconsole.team.dto;

/** Server-side F5 export query. Pagination is deliberately controlled by the server. */
public record F5CommissionExportRequest(
        String kind,
        String currency,
        String userId,
        String status,
        String cohort,
        String reason) {
}
