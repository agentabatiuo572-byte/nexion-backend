package ffdd.opsconsole.finance.domain;

public record DepositChannelView(
        String id,
        String code,
        String fee,
        String minAmount,
        Boolean enabled) {
}
