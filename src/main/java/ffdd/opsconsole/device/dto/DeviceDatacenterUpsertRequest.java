package ffdd.opsconsole.device.dto;

public record DeviceDatacenterUpsertRequest(
        String dcLocation,
        String regionLabel,
        String status,
        Integer sortOrder,
        String reason,
        String operator) {
}
