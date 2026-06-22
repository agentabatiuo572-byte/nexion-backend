package ffdd.opsconsole.content.dto;

public record NovaChannelStatusRequest(
        Boolean enabled,
        String operator,
        String reason) {
}
