package ffdd.opsconsole.team.dto;

public record TeamCommissionConfigUpdateRequest(
        String key,
        String value,
        String reason,
        String operator) {
}
