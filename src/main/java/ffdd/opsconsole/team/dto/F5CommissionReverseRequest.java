package ffdd.opsconsole.team.dto;

public record F5CommissionReverseRequest(
        String refundRef,
        String reason,
        String operator) {
}
