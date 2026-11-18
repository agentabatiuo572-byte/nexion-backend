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
        String reason,
        String displayName) {
    public EventSchemaRegistrationRequest(String eventName, String ownerDomain, String producer, String consumer,
            String propertyName, String propertyType, Boolean pii, Boolean serverAuthoritative,
            String samplingPolicy, String expectedVersion, String reason) {
        this(eventName, ownerDomain, producer, consumer, propertyName, propertyType, pii, serverAuthoritative,
                samplingPolicy, expectedVersion, reason, null);
    }
}
