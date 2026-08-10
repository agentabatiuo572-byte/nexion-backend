package ffdd.opsconsole.finance.dto;

public record PayoutVndChannelUpdateRequest(
        Boolean enabled,
        Long expectedVersion,
        String reason) {
}
