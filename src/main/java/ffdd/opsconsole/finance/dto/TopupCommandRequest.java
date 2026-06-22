package ffdd.opsconsole.finance.dto;

public record TopupCommandRequest(
        String value,
        Boolean enabled,
        String reason,
        String operator) {
}
