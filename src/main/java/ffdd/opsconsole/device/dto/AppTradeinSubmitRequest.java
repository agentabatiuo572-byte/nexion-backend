package ffdd.opsconsole.device.dto;

public record AppTradeinSubmitRequest(Long sourceDeviceId, Long targetProductId, String targetProductNo) {
    public AppTradeinSubmitRequest(Long sourceDeviceId, Long targetProductId) {
        this(sourceDeviceId, targetProductId, null);
    }
}
