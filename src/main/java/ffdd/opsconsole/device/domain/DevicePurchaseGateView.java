package ffdd.opsconsole.device.domain;

public record DevicePurchaseGateView(
        Integer rankMin,
        Integer activeDirectMin,
        java.math.BigDecimal teamComputePointsMin,
        String mode,
        Integer quotaCap,
        Integer quotaSold,
        String quotaPeriod,
        Boolean enforce) {
    public DevicePurchaseGateView {
        rankMin = rankMin == null ? 0 : rankMin;
        activeDirectMin = activeDirectMin == null ? 0 : activeDirectMin;
        teamComputePointsMin = teamComputePointsMin == null ? java.math.BigDecimal.ZERO : teamComputePointsMin;
    }
}
