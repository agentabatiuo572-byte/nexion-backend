package ffdd.opsconsole.risk.dto;

public record B5SignalStatusRequest(
        String targetStatus,
        String expectedStatus,
        Long expectedVersion,
        String reason,
        String operator) {
}
