package ffdd.opsconsole.device.dto;

public record DevicePhaseUpsertRequest(
        String phaseId,
        String label,
        String meta,
        String skus,
        Integer sortOrder,
        String status,
        String reason,
        String operator) {
}
