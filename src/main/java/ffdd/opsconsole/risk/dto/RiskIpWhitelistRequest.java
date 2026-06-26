package ffdd.opsconsole.risk.dto;

public record RiskIpWhitelistRequest(
        String cidr,
        String note,
        String expireText,
        String reason,
        String operator
) {
}
