package ffdd.opsconsole.platform.dto;

/** Adds one field to an already registered event without accepting mutable event metadata. */
public record ExistingSchemaPropertyRequest(
        String eventName,
        String propertyName,
        String propertyType,
        String expectedVersion,
        String reason) {
}
