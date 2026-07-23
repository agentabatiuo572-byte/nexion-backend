package ffdd.opsconsole.platform.dto;

public record EventSchemaRegistrationRequest(
        String eventName,
        String ownerDomain,
        String producer,
        String consumer,
        String propertyName,
        String propertyType,
        Boolean pii,
        Boolean serverAuthoritative,
        String samplingPolicy,
        String expectedVersion,
        String reason) {
}
