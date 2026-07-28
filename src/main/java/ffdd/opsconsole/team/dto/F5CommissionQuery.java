package ffdd.opsconsole.team.dto;

public record F5CommissionQuery(
        String kind,
        String currency,
        Long userId,
        String status,
        String cohort,
        Long cursor,
        Integer limit) {
}
