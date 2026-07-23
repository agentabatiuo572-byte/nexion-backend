package ffdd.opsconsole.platform.dto;

public record PlatformParamRegistrySourceState(
        String key,
        String label,
        String status,
        int rowCount,
        String detail) {
}
