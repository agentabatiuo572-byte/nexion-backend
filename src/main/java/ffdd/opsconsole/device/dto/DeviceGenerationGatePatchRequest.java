package ffdd.opsconsole.device.dto;

public record DeviceGenerationGatePatchRequest(
        String name,
        Integer releaseMonth,
        String phase,
        Boolean eligibility,
        Integer phaseOffset,
        Boolean forceUnlock,
        String status,
        String reason,
        String operator) {
}
