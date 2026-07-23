package ffdd.opsconsole.device.dto;

public record DeviceGenerationGateUpsertRequest(
        String skuId,
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
