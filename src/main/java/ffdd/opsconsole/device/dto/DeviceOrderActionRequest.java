package ffdd.opsconsole.device.dto;

import java.math.BigDecimal;

public record DeviceOrderActionRequest(
        String terminalState,
        String refundChannel,
        String reason,
        String operator,
        String refundReasonCode,
        String closeReasonCode,
        BigDecimal refundAmountUsdt) {

    public DeviceOrderActionRequest(String terminalState, String refundChannel, String reason, String operator,
                                    String refundReasonCode, String closeReasonCode) {
        this(terminalState, refundChannel, reason, operator, refundReasonCode, closeReasonCode, null);
    }

    public DeviceOrderActionRequest(String terminalState, String refundChannel, String reason, String operator,
                                    String refundReasonCode) {
        this(terminalState, refundChannel, reason, operator, refundReasonCode, null, null);
    }

    public DeviceOrderActionRequest(String terminalState, String refundChannel, String reason, String operator) {
        this(terminalState, refundChannel, reason, operator, null, null, null);
    }

    public DeviceOrderActionRequest(String terminalState, String reason, String operator) {
        this(terminalState, null, reason, operator, null, null, null);
    }
}
