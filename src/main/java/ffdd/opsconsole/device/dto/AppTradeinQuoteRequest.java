package ffdd.opsconsole.device.dto;

public record AppTradeinQuoteRequest(Long sourceDeviceId, Long targetProductId, String targetProductNo) {
    public AppTradeinQuoteRequest(Long sourceDeviceId, Long targetProductId) {
        this(sourceDeviceId, targetProductId, null);
    }
}
