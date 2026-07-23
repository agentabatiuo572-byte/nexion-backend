package ffdd.opsconsole.platform.dto;

public record EventDomainExtensionRequest(
        String domainName,
        String eventName,
        String producer,
        String consumer,
        String reason) {
}
