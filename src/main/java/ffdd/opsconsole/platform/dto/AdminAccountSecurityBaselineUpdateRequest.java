package ffdd.opsconsole.platform.dto;

public record AdminAccountSecurityBaselineUpdateRequest(
        String value,
        String reason,
        String operator) {
}
