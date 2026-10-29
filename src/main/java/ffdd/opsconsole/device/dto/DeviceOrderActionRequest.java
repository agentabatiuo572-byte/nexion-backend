package ffdd.opsconsole.device.dto;

public record DeviceOrderActionRequest(
        String terminalState,
        String refundChannel,
        String reason,
        String operator,
        String refundReasonCode,
        String closeReasonCode) {

    public DeviceOrderActionRequest(String terminalState, String refundChannel, String reason, String operator,
                                    String refundReasonCode) {
        this(terminalState, refundChannel, reason, operator, refundReasonCode, null);
    }

    public DeviceOrderActionRequest(String terminalState, String refundChannel, String reason, String operator) {
        this(terminalState, refundChannel, reason, operator, null, null);
    }

    public DeviceOrderActionRequest(String terminalState, String reason, String operator) {
        this(terminalState, null, reason, operator, null, null);
    }
}
