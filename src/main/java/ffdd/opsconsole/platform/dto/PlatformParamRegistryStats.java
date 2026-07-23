package ffdd.opsconsole.platform.dto;

public record PlatformParamRegistryStats(
        int registeredCount,
        int domainCount,
        int highSensitivityCount,
        int sourceCount) {
}
