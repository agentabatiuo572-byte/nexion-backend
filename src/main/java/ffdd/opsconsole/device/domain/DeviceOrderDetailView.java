package ffdd.opsconsole.device.domain;

import java.math.BigDecimal;
import java.time.LocalDateTime;
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
        LocalDateTime deviceActivatedAt,
        List<DeviceOrderHistoryView> history,
        List<DeviceOrderFundingView> funding,
        BigDecimal coverageCurrent,
        BigDecimal coverageRedline,
        BigDecimal coverageProjected,
        boolean refundAllowed,
        List<String> refundChannels,
        List<OrderDevice> devices,
        RefundImpact refundImpact) {
    public record TaskEarnings(long taskCount, BigDecimal usdtAmount, BigDecimal uvtAmount) { }
    public record RefundImpact(String scope, LocalDateTime asOf, TaskEarnings completedPending,
                               Long unfinishedTaskCount, long stoppedTaskCount, TaskEarnings creditedRetained,
                               boolean runtimeComplete, String runtimeUnavailableReason) { }
    public record OrderDevice(Long deviceId, String deviceInstanceNo, String kind, String sourceChannel,
                              String status, String ownershipStatus) {
    }
}
