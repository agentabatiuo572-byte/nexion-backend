package ffdd.opsconsole.finance.domain;

public record DepositBinRiskView(
        String segment,
        String meta,
        Long fails24h,
        Boolean locked,
        String note,
        Boolean manual) {
}
