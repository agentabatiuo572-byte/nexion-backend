package ffdd.opsconsole.bi.dto;

public record RegulatoryReportRequest(
        String templateCode,
        String period,
        String jurisdictionCode,
        String disclosureVersion,
        String recipient,
        String ticket,
        String reason,
        String operator) {
}

