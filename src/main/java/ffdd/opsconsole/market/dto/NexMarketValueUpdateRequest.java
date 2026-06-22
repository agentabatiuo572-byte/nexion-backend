package ffdd.opsconsole.market.dto;

public record NexMarketValueUpdateRequest(
        String value,
        String reason,
        String operator) {
}
