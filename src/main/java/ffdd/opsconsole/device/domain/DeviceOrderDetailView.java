package ffdd.opsconsole.device.domain;

import java.math.BigDecimal;
import java.util.List;

public record DeviceOrderDetailView(
        DeviceOrderView order,
        Long userId,
        Integer quantity,
        String orderType,
        BigDecimal subtotalUsdt,
        BigDecimal discountUsdt,
        String paymentNo,
        String paymentMethod,
        String paymentStatus,
        String orderStatus,
        String activationStatus,
        Long deviceId,
        String deviceInstanceNo,
        List<DeviceOrderHistoryView> history,
        List<DeviceOrderFundingView> funding,
        BigDecimal coverageCurrent,
        BigDecimal coverageRedline,
        BigDecimal coverageProjected,
        boolean refundAllowed,
        List<String> refundChannels) {
}
