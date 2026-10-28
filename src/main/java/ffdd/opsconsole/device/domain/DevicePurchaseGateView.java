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
}
