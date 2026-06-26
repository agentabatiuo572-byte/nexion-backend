package ffdd.opsconsole.bi.dto;

public record BiReportCreateRequest(
        String reason,
        String operator,
        String exportType,
        String timeRange,
        String fields,
        String piiLevel,
        String maskPolicy,
        String recipient,
        String ticket) {
}
