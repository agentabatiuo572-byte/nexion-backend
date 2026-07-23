package ffdd.opsconsole.platform.dto;

public record PlatformParamRegistryRow(
        String canonicalKey,
        String displayName,
        String description,
        String domain,
        String domainLabel,
        String ownerCode,
        String ownerLabel,
        String ownerRoute,
        String currentValue,
        String valueType,
        String unit,
        String source,
        String sourceStatus,
        String updatedAt,
        boolean operationConfirm,
        boolean serverCanonical) {
}
