package ffdd.opsconsole.team.domain;

public record VRankSkuFulfillmentRow(
        Long id,
        Long userId,
        String rankCode,
        String skuId,
        String status) {
}
