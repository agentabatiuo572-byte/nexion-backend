package ffdd.opsconsole.bi.dto;

public record BiDashboardValueRequest(
        String value,
        String reason,
        String operator) {
}
