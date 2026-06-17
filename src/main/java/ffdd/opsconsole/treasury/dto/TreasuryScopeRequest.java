package ffdd.opsconsole.treasury.dto;

public record TreasuryScopeRequest(
        String scope,
        String reason,
        String operator) {
}
