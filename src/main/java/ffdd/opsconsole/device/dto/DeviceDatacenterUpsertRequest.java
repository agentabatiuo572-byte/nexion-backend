package ffdd.opsconsole.device.dto;

public record DeviceDatacenterUpsertRequest(
        String dcLocation,
        String regionLabel,
        String status,
        Integer sortOrder,
        String reason,
        String operator,
        String location,
        String displayName) {

    public DeviceDatacenterUpsertRequest(
            String dcLocation, String regionLabel, String status, Integer sortOrder,
            String reason, String operator) {
        this(dcLocation, regionLabel, status, sortOrder, reason, operator, regionLabel, regionLabel);
    }
}
